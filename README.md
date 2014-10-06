Fast export of R models to PMML
===============================

A typical fast export workflow has the following steps:

1. Use R to train a model.
2. Serialize this model in [ProtoBuf data format] (https://code.google.com/p/protobuf/) to a file in local filesystem.
3. Use Java conversion application to turn this ProtoBuf file to a PMML file.

# The R side of operations #

The serialization is handled by the [`RProtoBuf` library] (http://cran.r-project.org/web/packages/RProtoBuf/).

The following R script trains a Random Forest (RF) model and saves it in ProtoBuf data format to a file `rf.pb`:
```R
library("randomForest")
library("RProtoBuf")

data(mydata)

rf = randomForest(target ~ ., data = mydata)

con = file("rf.pb", open = "wb")
serialize_pb(rf, con)
close(con)
```

# The Java side of operations #

Build the project using [Apache Maven] (http://maven.apache.org/):
```
mvn clean install
```

The build produces an uber-JAR file `target/export-1.0-SNAPSHOT.jar`, which contains a Java conversion application for every supported R model type. The conversion of RF models is handled by the class `org.jpmml.export.RandomForestConverter` (supports both regression- and classification-type models).

Converting the ProtoBuf file `rf.pb` to a PMML file `rf.pmml`:
```
java -Xms2048M -Xmx2048M -cp target/export-1.0-SNAPSHOT.jar org.jpmml.export.RandomForestConverter --pb-file rf.pb --pmml-file rf.pmml
```