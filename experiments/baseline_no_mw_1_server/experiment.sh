#! /usr/bin/env bash

TEST=false

# Client Instance Server
CONF=("1 1 6\n2 1 6\n3 1 6")
# Client 0 0 0
CLIENTS=("1 0 0\n2 0 0\n3 0 0")
# 0 0 0 Server
SERVERS=("0 0 6")

IPS=("6")

BASE_DIR="./"
SERVER_PORT=11211
CLIENT_THREADS=2
TEST_TIME=70

VCLIENTS=(1 4 8 16 24 32)
RATIOS=("1:0")
REPETITIONS=3

KEY_MAXIMUM=10000
DATA_SIZE=4096

TIMESTAMP=`date +%Y%m%d%H%M%S`
LOGS_DIR="${BASE_DIR}logs/baseline_no_mw_1_server/${TIMESTAMP}/"
CLIENT_ZIP_PATH="client{1}.zip"
LOCAL_LOGS_DIR="${BASE_DIR}logs/${TIMESTAMP}/"

CLIENT_HOST="pstefanoforaslvms{1}.westeurope.cloudapp.azure.com"
SERVER_HOST="pstefanoforaslvms{3}.westeurope.cloudapp.azure.com"
SERVER_ADDR="10.0.0.{1}"

CLIENT_ADDRESSES=(
  "pstefanoforaslvms1.westeurope.cloudapp.azure.com"
  "pstefanoforaslvms2.westeurope.cloudapp.azure.com"
  "pstefanoforaslvms3.westeurope.cloudapp.azure.com"
)

INSTANCES_PER_CLIENT=1

SERVER_ADDRESSES="10.0.0.6"

run_parallel() {
  echo -n "$1 ..."
  if $TEST
  then
    echo -e ""
    echo -e $2 | parallel --no-notice --colsep ' ' "echo '$3 $4'"
    echo -e "done\n"
  else
    echo -e $2 | parallel --no-notice --colsep ' ' "ssh '$3' '$4'"
    echo -e "\r$1, done\n"
  fi
}


scp_parallel() {
  echo -n "$1 ..."
  if $TEST
  then
    echo -e ""
    echo -e $2 | parallel --no-notice --colsep ' ' "echo '$3 $4'"
    echo -e "done\n"
  else
    echo -e $2 |  parallel --no-notice --colsep ' ' "scp '$3' '$4'"
    echo -e "\r$1, done\n"
  fi
}


run_parallel "Killing memtier" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}" \
             "sudo killall memtier_benchmark"

run_parallel "Killing memcached" \
             "${SERVERS[@]}" \
             "${SERVER_HOST}" \
             "sudo killall memcached"

run_parallel "Setting up log directories on clients" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}" \
             "mkdir -p ${LOGS_DIR}"

for ratio in ${RATIOS[@]}; do
  for run in `seq 1 ${REPETITIONS}`; do
    echo "Repetition ${run}  Ratio ${ratio}"
    echo "======================="

    run_parallel "Starting memcached on servers" \
                 "${SERVERS[@]}" \
                 "${SERVER_HOST}" \
                 "memcached -p ${SERVER_PORT} -t 1 > /dev/null 2>&1 &"

    # Do not populate for write-only workload
    if [ $ratio != "1:0" ];
    then

      # Use one of the clients to populate the servers
      args=("--server=${SERVER_ADDRESSES}"
            "--port=${SERVER_PORT}"
            "--protocol=memcache_text"
            "--threads=${CLIENT_THREADS}"
            "--ratio=1:0"
            "--clients=16"
            "--key-maximum=${KEY_MAXIMUM}"
            "--key-pattern=S:S"
            "--data-size=${DATA_SIZE}"
            "--expiry-range=9999-10000")
      args=${args[@]}

      run_parallel "Populating keys" \
                   "${IPS[@]}" \
                   "pstefanoforaslvms1.westeurope.cloudapp.azure.com" \
                   "memtier_benchmark-master/memtier_benchmark ${args} > /dev/null 2>&1 "

      KEY_MAXIMUM=9999

    fi;

    for vclients in ${VCLIENTS[@]}; do

      echo "Virtual Clients ${vclients}"
      echo "------------------"
      LOG_NAME="${LOGS_DIR}ratio${ratio}_run${run}_vclients${vclients}"

      args=("--server=${SERVER_ADDRESSES}"
            "--port=${SERVER_PORT}"
            "--protocol=memcache_text"
            "--threads=${CLIENT_THREADS}"
            "--test-time=${TEST_TIME}"
            "--ratio=${ratio}"
            "--clients=${vclients}"
            "--key-maximum=${KEY_MAXIMUM}"
            "--data-size=${DATA_SIZE}")
      args=${args[@]}

      run_parallel "Saving configurations" \
                   "${CONF[@]}" \
                   "${CLIENT_HOST}" \
                   "echo ${args} > ${LOG_NAME}_client{1}_instance{2}.conf"


      run_parallel "Running benchmarks" \
                   "${CONF[@]}" \
                   "${CLIENT_HOST}" \
                   "memtier_benchmark-master/memtier_benchmark ${args} > ${LOG_NAME}_client{1}_instance{2}.log 2>&1"


      run_parallel "Waiting for benchmarks to finish" \
                   "${CONF[@]}" \
                   "${CLIENT_HOST}" \
                   "while pgrep -f '^memtier_benchmark.*' > /dev/null; do sleep 1; done;"

    done;

    run_parallel "Killing memcached" \
                 "${SERVERS[@]}" \
                 "${SERVER_HOST}" \
                 "sudo killall memcached"

  done;
done;

run_parallel "Killing memcached" \
                 "${SERVERS[@]}" \
                 "${SERVER_HOST}" \
                 "sudo killall memcached"

run_parallel "Zipping client logs" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}" \
             "cd ${LOGS_DIR} && zip -r ${CLIENT_ZIP_PATH} ./"

mkdir -p ${LOCAL_LOGS_DIR}
scp_parallel "Copying client logs" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}:${LOGS_DIR}${CLIENT_ZIP_PATH}" \
             "${LOCAL_LOGS_DIR}"

cd ${LOCAL_LOGS_DIR} && unzip "*.zip"
