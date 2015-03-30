#!/bin/bash -e
#
# Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
# and other contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

pushd ../ui/console
mvn install -DskipTests
popd
pushd ../rest-servlet
mvn install -DskipTests
popd
pushd ../embedded-cassandra
mvn install -DskipTests
popd
mv ../embedded-cassandra/embedded-cassandra-ear/target/hawkular-metrics-embedded-cassandra.ear .
mv ../rest-servlet/target/hawkular-metric-rest*.war .
mv ../ui/console/target/metrics-console-*.war .
docker build --rm --tag hawkular-metrics:latest . 