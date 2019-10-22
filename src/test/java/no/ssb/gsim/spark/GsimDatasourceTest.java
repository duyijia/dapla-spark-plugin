package no.ssb.gsim.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.ssb.avro.convert.gsim.GsimBuilder;
import no.ssb.lds.gsim.okhttp.InstanceVariable;
import no.ssb.lds.gsim.okhttp.LogicalRecord;
import no.ssb.lds.gsim.okhttp.UnitDataStructure;
import no.ssb.lds.gsim.okhttp.UnitDataset;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.ssb.gsim.spark.GsimDatasource.CONFIG_LDS_URL;
import static no.ssb.gsim.spark.GsimDatasource.CONFIG_LOCATION_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

public class GsimDatasourceTest {

    private static final String UNIT_DATASET_ID = "b9c10b86-5867-4270-b56e-ee7439fe381e";
    private SQLContext sqlContext;
    private SparkContext sparkContext;
    private MockWebServer server;
    private MockResponse unitDatasetResponse;
    private File tempDirectory;

    @After
    public void tearDown() throws Exception {
        sparkContext.stop();
    }

    @Before
    public void setUp() throws Exception {
        // Create temporary folder and copy test data into it.
        tempDirectory = Files.createTempDirectory("lds-gsim-spark").toFile();
        InputStream parquetContent = this.getClass().getResourceAsStream("data/dataset.parquet");
        Path parquetFile = tempDirectory.toPath().resolve("dataset.parquet");
        Files.copy(parquetContent, parquetFile);

        // Setup a mock lds server.
        this.server = new MockWebServer();
        this.server.start();
        HttpUrl baseUrl = server.url("/lds/");

        // Read the unit dataset json example.
        InputStream in = this.getClass().getResourceAsStream("data/UnitDataSet_Person_1.json");
        String json = new Buffer().readFrom(in).readByteString().utf8();
        json = json.replaceAll("%DATA_PATH%", parquetFile.toString());
        unitDatasetResponse = new MockResponse().setBody(json).setResponseCode(200);

        SparkSession session = SparkSession.builder()
                .appName(GsimDatasourceTest.class.getSimpleName())
                .master("local")
                .config("spark.ui.enabled", false)
                .config(CONFIG_LDS_URL, baseUrl.toString())
                .config(CONFIG_LOCATION_PREFIX, tempDirectory.toString())
                .getOrCreate();

        this.sparkContext = session.sparkContext();
        this.sqlContext = session.sqlContext();

    }

    @Test
    public void testReadWithId() {
        this.server.enqueue(unitDatasetResponse);
        Dataset<Row> dataset = sqlContext.read()
                .format("no.ssb.gsim.spark")
                .load("lds+gsim://" + UNIT_DATASET_ID);
        dataset.show();
    }

    @Test
    public void testWriteWithId() {
        this.server.enqueue(unitDatasetResponse);
        this.server.enqueue(unitDatasetResponse);
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(unitDatasetResponse);

        Dataset<Row> dataset = sqlContext.read()
                .format("no.ssb.gsim.spark")
                .load("lds+gsim://" + UNIT_DATASET_ID);
        dataset.printSchema();
        dataset.show();

        dataset.write().format("no.ssb.gsim.spark").mode(SaveMode.Append).save("lds+gsim://" + UNIT_DATASET_ID);

    }

    @Test
    public void testWriteAndCreateOfLdsObjects() throws InterruptedException, IOException {
        this.server.enqueue(unitDatasetResponse);
        this.server.enqueue(unitDatasetResponse);
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(unitDatasetResponse);
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));
        this.server.enqueue(new MockResponse().setResponseCode(201));

        Dataset<Row> dataset = sqlContext.read()
                .format("no.ssb.gsim.spark")
                .load("lds+gsim://" + UNIT_DATASET_ID);
        dataset.printSchema();
        dataset.show();

        dataset.write()
                .format("no.ssb.gsim.spark")
                .mode(SaveMode.Overwrite)
                .option(DatasetHelper.CRATE_GSIM_OBJECTS, "true")
                .option(DatasetHelper.CREATE_DATASET, "dataset_name")
                .option(DatasetHelper.DESCRIPTION, "description of dataset")
                .save();

        assertThat(getResponse().getMethod()).isEqualTo("GET");

        checkUnitDataStructureResponse(dataStructure -> {
            assertThat(dataStructure.getLogicalRecords().size()).isEqualTo(1);
        });

        checkUnitDataSetResponse(unitDataset -> {
            List<Map<String, String>> name = GsimBuilder.createListOfMap("nb", "dataset_name");
            assertThat(unitDataset.getUnknownProperties().get("name")).isEqualTo(name);

            List<Map<String, String>> description = GsimBuilder.createListOfMap("nb", "description of dataset");
            assertThat(unitDataset.getUnknownProperties().get("description")).isEqualTo(description);
            assertThat(unitDataset.getDataSourcePath()).isEqualTo("/path");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("PERSON_ID");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("INCOME");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("GENDER");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("MARITAL_STATUS");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("MUNICIPALITY");
        });

        checkInstanceVariableResponse(instanceVariable -> {
            assertThat(instanceVariable.getShortName()).isEqualTo("DATA_QUALITY");
        });

        checkLogicalRecordResponse(logicalRecord -> {
            assertThat(logicalRecord.getShortName()).isEqualTo("spark_schema");
        });

        // Check that we have getDataSourcePath updated to correct path after parquet file is saved
        checkUnitDataSetResponse(unitDataset -> {
            assertThat(unitDataset.getDataSourcePath()).contains(tempDirectory.toString());
        });
    }

    interface UnitDatasetAction {
        void onRequest(UnitDataset unitDataset);
    }

    interface UnitDataStructureAction {
        void onRequest(UnitDataStructure dataStructure);
    }

    interface LogicalRecordAction {
        void onRequest(LogicalRecord logicalRecord);
    }

    interface InstanceVariableAction {
        void onRequest(InstanceVariable instanceVariable);
    }

    private void checkUnitDataSetResponse(UnitDatasetAction unitDatasetAction) throws IOException, InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        ObjectMapper mapper = new ObjectMapper();
        Class<UnitDataset> type = UnitDataset.class;
        assertThat(recordedRequest.getPath()).contains(UnitDataset.UNIT_DATA_SET_NAME);
        unitDatasetAction.onRequest(mapper.readValue(recordedRequest.getBody().readByteArray(), type));
    }

    private void checkUnitDataStructureResponse(UnitDataStructureAction action) throws IOException, InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        ObjectMapper mapper = new ObjectMapper();
        Class<UnitDataStructure> type = UnitDataStructure.class;
        assertThat(recordedRequest.getPath()).contains(type.getSimpleName());
        action.onRequest(mapper.readValue(recordedRequest.getBody().readByteArray(), type));
    }

    private void checkLogicalRecordResponse(LogicalRecordAction action) throws IOException, InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        ObjectMapper mapper = new ObjectMapper();
        Class<LogicalRecord> type = LogicalRecord.class;
        assertThat(recordedRequest.getPath()).contains(type.getSimpleName());
        action.onRequest(mapper.readValue(recordedRequest.getBody().readByteArray(), type));
    }

    private void checkInstanceVariableResponse(InstanceVariableAction action) throws IOException, InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        ObjectMapper mapper = new ObjectMapper();
        Class<InstanceVariable> type = InstanceVariable.class;
        assertThat(recordedRequest.getPath()).contains(type.getSimpleName());
        action.onRequest(mapper.readValue(recordedRequest.getBody().readByteArray(), type));
    }

    private RecordedRequest getResponse() throws InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        String json = recordedRequest.getBody().readByteString().utf8();
        System.out.println(json);

        return recordedRequest;
    }
}