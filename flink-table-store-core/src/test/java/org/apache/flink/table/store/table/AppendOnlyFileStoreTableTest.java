/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.table;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.runtime.typeutils.RowDataSerializer;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.file.WriteMode;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.predicate.PredicateBuilder;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.schema.UpdateSchema;
import org.apache.flink.table.store.table.sink.TableCommit;
import org.apache.flink.table.store.table.sink.TableWrite;
import org.apache.flink.table.store.table.source.Split;
import org.apache.flink.table.store.table.source.TableRead;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.apache.flink.table.store.table.sink.SinkRecordConverter.bucket;
import static org.apache.flink.table.store.table.sink.SinkRecordConverter.hashcode;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link AppendOnlyFileStoreTable}. */
public class AppendOnlyFileStoreTableTest extends FileStoreTableTestBase {

    @Test
    public void testBatchReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = table.newScan().plan().splits;
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList("1|10|100", "1|11|101", "1|12|102", "1|11|101", "1|12|102"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(Arrays.asList("2|20|200", "2|21|201", "2|22|202", "2|21|201"));
    }

    @Test
    public void testBatchProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = table.newScan().plan().splits;
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .hasSameElementsAs(Arrays.asList("100|10", "101|11", "102|12", "101|11", "102|12"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .hasSameElementsAs(Arrays.asList("200|20", "201|21", "202|22", "201|21"));
    }

    @Test
    public void testBatchFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = builder.equal(2, 201L);
        List<Split> splits = table.newScan().withFilter(predicate).plan().splits;
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "2|21|201",
                                // this record is in the same file with the first "2|21|201"
                                "2|22|202",
                                "2|21|201"));
    }

    @Test
    public void testStreamingReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = table.newScan().withIncremental(true).plan().splits;
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("+1|11|101", "+1|12|102"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("+2|21|201"));
    }

    @Test
    public void testStreamingProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = table.newScan().withIncremental(true).plan().splits;
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("+101|11", "+102|12"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("+201|21"));
    }

    @Test
    public void testStreamingFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = builder.equal(2, 101L);
        List<Split> splits =
                table.newScan().withIncremental(true).withFilter(predicate).plan().splits;
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "+1|11|101",
                                // this record is in the same file with "+1|11|101"
                                "+1|12|102"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING)).isEmpty();
    }

    @Test
    public void testSequentialRead() throws Exception {
        Random random = new Random();
        int numOfBucket = Math.max(random.nextInt(8), 1);
        FileStoreTable table = createFileStoreTable(numOfBucket);
        RowDataSerializer serializer = new RowDataSerializer(table.schema().logicalRowType());
        TableWrite write = table.newWrite();

        TableCommit commit = table.newCommit("user");
        List<Map<Integer, List<RowData>>> dataset = new ArrayList<>();
        Map<Integer, List<RowData>> dataPerBucket = new HashMap<>(numOfBucket);
        int numOfPartition = Math.max(random.nextInt(10), 1);
        for (int i = 0; i < numOfPartition; i++) {
            for (int j = 0; j < Math.max(random.nextInt(200), 1); j++) {
                BinaryRowData data =
                        serializer
                                .toBinaryRow(
                                        GenericRowData.of(i, random.nextInt(), random.nextLong()))
                                .copy();
                int bucket = bucket(hashcode(data), numOfBucket);
                dataPerBucket.compute(
                        bucket,
                        (k, v) -> {
                            if (v == null) {
                                v = new ArrayList<>();
                            }
                            v.add(data);
                            return v;
                        });
                write.write(data);
            }
            dataset.add(new HashMap<>(dataPerBucket));
            dataPerBucket.clear();
        }
        commit.commit("0", write.prepareCommit(true));

        int partition = random.nextInt(numOfPartition);
        List<Integer> availableBucket = new ArrayList<>(dataset.get(partition).keySet());
        int bucket = availableBucket.get(random.nextInt(availableBucket.size()));

        Predicate partitionFilter =
                new PredicateBuilder(table.schema().logicalRowType()).equal(0, partition);
        List<Split> splits =
                table.newScan().withFilter(partitionFilter).withBucket(bucket).plan().splits;
        TableRead read = table.newRead();
        getResult(read, splits, binaryRow(partition), bucket, STREAMING_ROW_TO_STRING);

        assertThat(getResult(read, splits, binaryRow(partition), bucket, STREAMING_ROW_TO_STRING))
                .containsExactlyElementsOf(
                        dataset.get(partition).get(bucket).stream()
                                .map(STREAMING_ROW_TO_STRING)
                                .collect(Collectors.toList()));
    }

    private void writeData() throws Exception {
        FileStoreTable table = createFileStoreTable();
        TableWrite write = table.newWrite();
        TableCommit commit = table.newCommit("user");

        write.write(GenericRowData.of(1, 10, 100L));
        write.write(GenericRowData.of(2, 20, 200L));
        write.write(GenericRowData.of(1, 11, 101L));
        commit.commit("0", write.prepareCommit(true));

        write.write(GenericRowData.of(1, 12, 102L));
        write.write(GenericRowData.of(2, 21, 201L));
        write.write(GenericRowData.of(2, 22, 202L));
        commit.commit("1", write.prepareCommit(true));

        write.write(GenericRowData.of(1, 11, 101L));
        write.write(GenericRowData.of(2, 21, 201L));
        write.write(GenericRowData.of(1, 12, 102L));
        commit.commit("2", write.prepareCommit(true));

        write.close();
    }

    @Override
    protected FileStoreTable createFileStoreTable(Consumer<Configuration> configure)
            throws Exception {
        Configuration conf = new Configuration();
        conf.set(CoreOptions.PATH, tablePath.toString());
        conf.set(CoreOptions.WRITE_MODE, WriteMode.APPEND_ONLY);
        configure.accept(conf);
        SchemaManager schemaManager = new SchemaManager(tablePath);
        TableSchema tableSchema =
                schemaManager.commitNewVersion(
                        new UpdateSchema(
                                ROW_TYPE,
                                Collections.singletonList("pt"),
                                Collections.emptyList(),
                                conf.toMap(),
                                ""));
        return new AppendOnlyFileStoreTable(tablePath, schemaManager, tableSchema);
    }
}
