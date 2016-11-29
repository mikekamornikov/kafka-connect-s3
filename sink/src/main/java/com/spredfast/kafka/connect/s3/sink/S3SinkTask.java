package com.spredfast.kafka.connect.s3.sink;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.IllegalWorkerStateException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.storage.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.amazonaws.services.s3.AmazonS3;
import com.spredfast.kafka.connect.s3.AlreadyBytesConverter;
import com.spredfast.kafka.connect.s3.Configure;
import com.spredfast.kafka.connect.s3.Constants;
import com.spredfast.kafka.connect.s3.Metrics;
import com.spredfast.kafka.connect.s3.S3;
import com.spredfast.kafka.connect.s3.S3RecordFormat;
import com.spredfast.kafka.connect.s3.S3RecordsWriter;


public class S3SinkTask extends SinkTask {

	private static final Logger log = LoggerFactory.getLogger(S3SinkTask.class);

	private Map<String, String> config;

	private final Map<TopicPartition, BlockGZIPFileWriter> tmpFiles = new HashMap<>();
	private final Map<TopicPartition, S3RecordsWriter> writers = new HashMap<>();

	private long GZIPChunkThreshold = 67108864;

	private S3Writer s3;

	private Optional<Converter> keyConverter;

	private Converter valueConverter;

	private S3RecordFormat recordFormat;

	private Metrics metrics;

	private Map<String, String> tags;

	@Override
	public String version() {
		return Constants.VERSION;
	}

	@Override
	public void start(Map<String, String> props) throws ConnectException {
		config = new HashMap<>(props);

		configGet("compressed_block_size").map(Long::parseLong).ifPresent(chunkThreshold ->
			this.GZIPChunkThreshold = chunkThreshold);

		recordFormat = Configure.createFormat(props);

		keyConverter = ofNullable(Configure.buildConverter(config, "key.converter", true, null));
		valueConverter = Configure.buildConverter(config, "value.converter", false, AlreadyBytesConverter.class);

		String bucket = configGet("s3.bucket")
			.filter(s -> !s.isEmpty())
			.orElseThrow(() -> new ConnectException("S3 bucket must be configured"));
		String prefix = configGet("s3.prefix")
			.orElse("");
		AmazonS3 s3Client = S3.s3client(config);

		s3 = new S3Writer(bucket, prefix, s3Client);

		metrics = Configure.metrics(props);
		tags = Configure.parseTags(props.get("metrics.tags"));
		tags.put("connector_name", name());

		// Recover initial assignments
		open(context.assignment());
	}

	private Optional<String> configGet(String key) {
		return ofNullable(config.get(key));
	}

	@Override
	public void stop() throws ConnectException {
		// delete our temp files
		for (BlockGZIPFileWriter writer : tmpFiles.values()) {
			try {
				log.debug("{} Stopping - Deleting temp file {}", name(), writer.getDataFilePath());
				writer.delete();
			} catch (IOException e) {
				log.warn("Error deleting temp file " + writer.getDataFilePath(), e);
			}
		}
	}


	private void writeAll(Collection<SinkRecord> records, BlockGZIPFileWriter buffer, S3RecordsWriter writer) {
		metrics.hist(records.size(), "putSize", buffer.tags());
		try (Metrics.StopTimer ignored = metrics.time("writeAll", buffer.tags())) {
			buffer.write(writer.writeBatch(records.stream().map(record -> new ProducerRecord<>(record.topic(), record.kafkaPartition(),
				keyConverter.map(c -> c.fromConnectData(record.topic(), record.keySchema(), record.key()))
					.orElse(null),
				valueConverter.fromConnectData(record.topic(), record.valueSchema(), record.value())
			))).collect(toList()));
		} catch (IOException e) {
			throw new RetriableException("Failed to write to buffer", e);
		}
	}

	@Override
	public void put(Collection<SinkRecord> records) throws ConnectException {
		records.stream().collect(groupingBy(record -> new TopicPartition(record.topic(), record.kafkaPartition()))).forEach((tp, rs) -> {
			BlockGZIPFileWriter buffer = tmpFiles.get(tp);
			if (buffer == null) {
				log.error("Trying to put {} records to partition {} which doesn't exist yet", records.size(), tp);
				throw new ConnectException("Trying to put records for a topic partition that has not be assigned");
			}
			log.debug("{} received {} records for {} to archive. Last offset {}", name(), rs.size(), tp,
				rs.get(rs.size() - 1).kafkaOffset());
			writerGet(tp).ifPresent(w -> writeAll(rs, buffer, w));
		});
	}

	@Override
	public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) throws ConnectException {
		Metrics.StopTimer timer = metrics.time("flush", tags);
		// Don't rely on offsets passed. They have some quirks like including topic partitions that just
		// got revoked (i.e. we have deleted the writer already). Not sure if this is intended...
		// https://twitter.com/mr_paul_banks/status/702493772983177218
		log.debug("{} flushing offsets", name());

		// Instead iterate over the writers we do have and get the offsets directly from them.
		for (Map.Entry<TopicPartition, BlockGZIPFileWriter> entry : tmpFiles.entrySet()) {
			TopicPartition tp = entry.getKey();
			BlockGZIPFileWriter writer = entry.getValue();
			if (writer.getNumRecords() == 0) {
				continue;
			}
			try {
				Metrics.StopTimer time = metrics.time("s3Put", writer.tags());

				finishWriter(writer, tp);
				long nextOffset = s3.putChunk(writer.getDataFilePath(), writer.getIndexFilePath(), tp);

				log.debug("{} Finished {} to {} - Deleting temp file {}", name(), tp, nextOffset, writer.getDataFilePath());
				writer.delete();

				time.stop();

				// Now reset writer to a new one
				initWriter(tp, nextOffset);

				log.debug("{} successfully uploaded {} records for {} as {} now at offset {}", name(), writer.getNumRecords(), tp,
					writer.getDataFileName(), nextOffset);
			} catch (FileNotFoundException fnf) {
				throw new ConnectException("Failed to find local dir for temp files", fnf);
			} catch (IOException e) {
				throw new RetriableException("Failed S3 upload", e);
			}
		}
		timer.stop();
	}

	private String name() {
		return configGet("name").orElseThrow(() -> new IllegalWorkerStateException("Tasks always have names"));
	}

	public void finishWriter(BlockGZIPFileWriter writer, TopicPartition tp) throws IOException {
		writerGet(tp).ifPresent(w -> w.finish(tp.topic(), tp.partition()));
		writers.remove(tp);
		writer.close();
	}

	private Optional<S3RecordsWriter> writerGet(TopicPartition tp) {
		return ofNullable(writers.get(tp));
	}

	private BlockGZIPFileWriter createNextBlockWriter(TopicPartition tp, long nextOffset, S3RecordsWriter newWriter) throws ConnectException, IOException {
		String name = String.format("%s-%05d", tp.topic(), tp.partition());
		String path = configGet("local.buffer.dir")
			.orElseThrow(() -> new ConnectException("No local buffer file path configured"));
		log.debug("New temp file: {}/{} @ {}", path, name, nextOffset);
		Map<String, String> tags = new HashMap<>(this.tags);
		tags.put("kafka_topic", tp.topic());
		tags.put("kafka_partition", "" + tp.partition());
		return new BlockGZIPFileWriter(name, path, nextOffset, this.GZIPChunkThreshold, newWriter.init(tp.topic(), tp.partition(), nextOffset), tags);
	}

	@Override
	public void close(Collection<TopicPartition> partitions) {
		for (TopicPartition tp : partitions) {
			// See if this is a new assignment
			BlockGZIPFileWriter writer = this.tmpFiles.remove(tp);
			if (writer != null) {
				log.info("Revoked partition {} Deleting temp file {}", tp, writer.getDataFileName());
				try {
					finishWriter(writer, tp);
					writer.delete();
				} catch (IOException ioe) {
					throw new ConnectException("Failed to resume TopicPartition form S3", ioe);
				}
			}
		}
	}

	@Override
	public void open(Collection<TopicPartition> partitions) {
		for (TopicPartition tp : partitions) {
			// See if this is a new assignment
			if (this.tmpFiles.get(tp) == null) {
				log.info("Assigned new partition {} creating buffer writer", tp);
				try {
					recoverPartition(tp);
				} catch (IOException ioe) {
					throw new ConnectException("Failed to resume TopicPartition from S3", ioe);
				}
			}
		}
	}

	private void recoverPartition(TopicPartition tp) throws IOException {
		this.context.pause(tp);

		// Recover last committed offset from S3
		long offset = s3.fetchOffset(tp);

		log.info("Recovering partition {} from offset {}", tp, offset);

		initWriter(tp, offset);

		this.context.offset(tp, offset);
		this.context.resume(tp);
	}

	private void initWriter(TopicPartition tp, long offset) throws IOException {
		S3RecordsWriter newWriter = recordFormat.newWriter();
		tmpFiles.put(tp, createNextBlockWriter(tp, offset, newWriter));
		writers.put(tp, newWriter);
	}

}
