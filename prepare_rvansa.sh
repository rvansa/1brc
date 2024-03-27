#!/bin/bash
#
#  Copyright 2023 The original authors
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

# Uncomment below to use sdk
if [ -n "$CRAC_HOME" ]; then
  export JAVA_HOME=$CRAC_HOME
  export PATH=$JAVA_HOME/bin/:$PATH
else
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  sdk use java 21.0.2.crac-zulu 1>&2
fi

FORK=${CRAC_FORK:-baseline}
if [ ! -f "measurements.txt" ]; then
  ln -s "measurements_1B.txt" "measurements.txt"
fi

if [ -f prepare_${FORK}.sh ]; then
  cat prepare_${FORK}.sh \
    | grep -v -e 'sdkman-init.sh' \
    | grep -v -e 'sdk use java' \
    > target/prepare.sh
  chmod a+x target/prepare.sh
  target/prepare.sh > /dev/null 2>&1
fi

echo "Creating CRaC image..."
rm -rf target/crac_image 2> /dev/null
cat calculate_average_${FORK}.sh \
  | sed 's/ \(dev.morling.onebrc.CalculateAverage_[0-9a-zA-Z_]*\)/ dev.morling.onebrc.CalculateAverage_rvansa \1/' \
  | sed 's/-XX:-TieredCompilation//' \
  | sed 's/JAVA_OPTS="\([^$]\)/JAVA_OPTS="'${CR_ENGINE_LIBRARY}' -XX:CRaCCheckpointTo=target\/crac_image \1/' \
  > target/checkpoint.sh
chmod a+x target/checkpoint.sh
target/checkpoint.sh

while ps fa | grep "XX[:]CRaCCheckpointTo" > /dev/null; do
  sleep 1
done
