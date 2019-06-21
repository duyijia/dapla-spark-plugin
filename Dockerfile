FROM apache/zeppelin:0.8.1


# Copy the library.
COPY target/lds-gsim-spark-1.0-SNAPSHOT.jar /zeppelin/lib/lds-gsim-spark.jar

# Copy zeppelin settings. This includes the library.
# docker exec ID-OF-CONTAINER cat /zeppelin/conf/interpreter.json > interpreter.json
COPY interpreter.json /zeppelin/conf/interpreter.json

# Add the GCS connector.
# https://github.com/GoogleCloudPlatform/bigdata-interop/blob/master/gcs/INSTALL.md#configure-spark
# https://github.com/GoogleCloudPlatform/bigdata-interop/issues/188
# https://github.com/GoogleCloudPlatform/bigdata-interop/pull/180/files
RUN wget https://storage.googleapis.com/hadoop-lib/gcs/gcs-connector-hadoop2-latest.jar
RUN mv gcs-connector-hadoop2-latest.jar lib/gcs-connector-hadoop.jar

VOLUME ["/gcloud/key.json"]

# Add some examples:
# val test = spark.read.parquet("gs://ssb-data-a/data/b9c10b86-5867-4270-b56e-ee7439fe381e")
# test.show()
#
# val test = spark.read.format("no.ssb.gsim.spark")
#     .load("lds+gsim://b9c10b86-5867-4270-b56e-ee7439fe381e")
# test.show()