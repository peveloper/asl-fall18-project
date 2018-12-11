#! /usr/bin/env bash

TEST=false

# Client Instance Middleware Server
INSTANCES=("1 1 4\n1 2 5\n2 1 4\n2 2 5\n3 1 4\n3 2 5")
# Client 0 0 0
CLIENTS=("1 0 0 0\n2 0 0 0\n3 0 0 0")
# 0 0 0 Server
SERVERS=("0 0 0 6\n0 0 0 7\n0 0 0 8")
# 0 0 Middleware 0
MIDDLEWARES=("0 0 4 0\n0 0 5 0")

IPS=("6\n10\n7")

BASE_DIR="./"
MIDDLEWARE_DIR="asl/middleware/dist/"
MIDDLEWARE_CMD="java -jar ${MIDDLEWARE_DIR}middleware-pstefano.jar"
MIDDLEWARE_LOGS_DIR="logs/"
MIDDLEWARE_PORT=8888
SERVER_PORT=11211
CLIENT_THREADS=1
TEST_TIME=70

VCLIENTS=(32)
RATIOS=("0:1")
REPETITIONS=3
WORKER_THREADS=(8 32) 
SHARDED="false"

KEY_MAXIMUM=10000
DATA_SIZE=4096

TIMESTAMP=`date +%Y%m%d%H%M%S`
LOGS_DIR="${BASE_DIR}logs/throughput_for_writes/${TIMESTAMP}/"
CLIENT_ZIP_PATH="client{1}.zip"
MIDDLEWARE_ZIP_PATH="middleware{3}.zip"
LOCAL_LOGS_DIR="${BASE_DIR}logs/${TIMESTAMP}/"

CLIENT_HOST="pstefanoforaslvms{1}.westeurope.cloudapp.azure.com"
MIDDLEWARE_HOST="pstefanoforaslvms{3}.westeurope.cloudapp.azure.com"
SERVER_HOST="pstefanoforaslvms{4}.westeurope.cloudapp.azure.com"
SERVER_ADDR="10.0.0.{1}"
MIDDLEWARE_ADDR="pstefanoforaslvms{3}.westeurope.cloudapp.azure.com"

CLIENT_ADDRESSES=(
  "pstefanoforaslvms1.westeurope.cloudapp.azure.com"
  "pstefanoforaslvms2.westeurope.cloudapp.azure.com"
  "pstefanoforaslvms3.westeurope.cloudapp.azure.com"
)
INSTANCES_PER_CLIENT=2

MIDDLEWARE_HOSTS=(
  "10.0.0.4"
  "10.0.0.11"
)

MIDDLEWARE_ADDRESSES=(
  "pstefanoforaslvms4.westeurope.cloudapp.azure.com"
  "pstefanoforaslvms5.westeurope.cloudapp.azure.com"
)

SERVER_ADDRESSES="10.0.0.6:${SERVER_PORT} 10.0.0.10:${SERVER_PORT} 10.0.0.7:${SERVER_PORT}"

run_parallel() {
  echo -n "$1 ..."
  if $TEST
  then
    echo -e ""
    echo -e $2 | parallel --no-notice --colsep ' ' "echo '$3 $4'"
    echo -e "done\n"
  else
    echo -e $2 | parallel --no-notice --colsep ' ' "echo '$3 $4'"
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
    #echo -e $2 | parallel --no-notice --colsep ' ' "echo '$3 $4'"
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

run_parallel "Setting up log directories on middlewares" \
             "${MIDDLEWARES[@]}" \
             "${MIDDLEWARE_HOST}" \
             "mkdir -p ${LOGS_DIR}"

run_parallel "Stopping middlewares" \
             "${MIDDLEWARES[@]}" \
             "${MIDDLEWARE_HOST}" \
             "pkill -f '^java.*'"


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
      args=("--server=${SERVER_ADDR}"
            "--port=${SERVER_PORT}"
            "--protocol=memcache_text"
            "--threads=${CLIENT_THREADS}"
            "--ratio=1:0"
            "--clients=16"
            "--key-maximum=${KEY_MAXIMUM}"
            "--key-pattern=P:P"
            "--data-size=${DATA_SIZE}"
            "--expiry-range=9999-10000")
      args=${args[@]}

      run_parallel "Populating keys" \
                   "${IPS[@]}" \
                   "pstefanoforaslvms1.westeurope.cloudapp.azure.com" \
                   "memtier_benchmark-master/memtier_benchmark ${args} > /dev/null 2>&1 "

    fi;

    for worker_threads in ${WORKER_THREADS[@]}; do
      for vclients in ${VCLIENTS[@]}; do
          echo "Worker Threads ${worker_threads} Virtual Clients ${vclients}"

          echo "Starting middlewares"
          for mw in ${MIDDLEWARE_ADDRESSES[@]}; do
            ip=`ssh "${mw}" "hostname -i"`
            #echo 'ip=`ssh "${mw}" "hostname -i"`'
            args=("-l ${ip}"
                  "-p ${MIDDLEWARE_PORT}"
                  "-t ${worker_threads}"
                  "-s ${SHARDED}"
                  "-m ${SERVER_ADDRESSES}")
            args=${args[@]}

            ssh "${mw}" "${MIDDLEWARE_CMD} ${args} > /dev/null 2>&1" &
            #echo "ssh ${mw}" "${MIDDLEWARE_CMD} ${args} > /dev/null 2>&1" &
          done;

          echo "Waiting for middlewares to have started"
          for mw in ${MIDDLEWARE_ADDRESSES[@]}; do
            ssh "${mw}" "while pgrep -f '^java.*' > /dev/null; [ $? -ne 0 ]; do sleep 1; done;"
          done;

          echo "Waiting for 8 seconds"
          sleep 8s;


          LOG_NAME="${LOGS_DIR}ratio${ratio}_run${run}_vclients${vclients}_workerthreads${worker_threads}"

          args=${args[@]}

          run_parallel "Saving configurations" \
                       "${INSTANCES[@]}" \
                       "${CLIENT_HOST}" \
                       "echo ${args} > ${LOG_NAME}_client{1}_instance{2}.conf"


            echo "Starting benchmarks"
            client_idx=1
            for client in ${CLIENT_ADDRESSES[@]}; do
              instance_idx=1
              for mw in ${MIDDLEWARE_HOSTS[@]}; do
                echo "Starting ${client} to ${mw}"
                args=("--server=${mw}"
                      "--port=${MIDDLEWARE_PORT}"
                      "--protocol=memcache_text"
                      "--threads=${CLIENT_THREADS}"
                      "--test-time=${TEST_TIME}"
                      "--ratio=${ratio}"
                      "--clients=${vclients}"
                      "--key-maximum=${KEY_MAXIMUM}"
                      "--data-size=${DATA_SIZE}")
                args=${args[@]}

                ssh ${client} "echo ${args} > ${LOG_NAME}_client${client_idx}_instance${instance_idx}.conf" &
                ssh ${client} "memtier_benchmark-master/memtier_benchmark ${args} > ${LOG_NAME}_client${client_idx}_instance${instance_idx}.log 2>&1 " &

                instance_idx=$((${instance_idx} + 1))
              done;
              client_idx=$((${client_idx} + 1))
            done;


            echo "Waiting for benchmarks to finish"
            for client in ${CLIENT_ADDRESSES[@]}; do
              ssh "${client}" "while pgrep -f '^memtier_benchmark.*' > /dev/null; do sleep 1; done;"
              #echo "${client}" "while pgrep -f '^memtier_benchmark.*' > /dev/null; do sleep 1; done;"
            done;




          run_parallel "Killing middleware" \
                       "${MIDDLEWARES[@]}" \
                       "${MIDDLEWARE_HOST}" \
                       "pkill -f '^java.*'"


          echo "Waiting for middlewares to exit"
          for mw in ${MIDDLEWARE_ADDRESSES[@]}; do
            ssh "${mw}" "while pgrep -f '^java.*' > /dev/null; do sleep 1; done;"
            #echo "ssh ${mw}" "while pgrep -f '^java.*' > /dev/null; do sleep 1; done;"
          done;


          ADD="_ratio${ratio}_run${run}_vclients${vclients}_workerthreads${worker_threads}.log"
          run_parallel "Renaming middleware logs" \
                       "${MIDDLEWARES[@]}" \
                       "${MIDDLEWARE_HOST}" \
                       "cp '${MIDDLEWARE_LOGS_DIR}stats.log' '${LOGS_DIR}stats${ADD}' && cp '${MIDDLEWARE_LOGS_DIR}middleware.log' '${LOGS_DIR}middleware${ADD}'"
          echo ""
      done;
    done;

    run_parallel "Killing memcached" \
                 "${SERVERS[@]}" \
                 "${SERVER_HOST}" \
                 "sudo killall memcached"
  done;
done;

run_parallel "Zipping client logs" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}" \
             "cd ${LOGS_DIR} && zip -r ${CLIENT_ZIP_PATH} ./"

mkdir -p ${LOCAL_LOGS_DIR}
scp_parallel "Copying client logs" \
             "${CLIENTS[@]}" \
             "${CLIENT_HOST}:${LOGS_DIR}${CLIENT_ZIP_PATH}" \
             "${LOCAL_LOGS_DIR}"

run_parallel "Zipping middleware logs" \
             "${MIDDLEWARES[@]}" \
             "${MIDDLEWARE_HOST}" \
             "cd ${LOGS_DIR} && zip -r ${MIDDLEWARE_ZIP_PATH} ./" 

scp_parallel "Copying middleware logs" \
             "${MIDDLEWARES[@]}" \
             "${MIDDLEWARE_HOST}:${LOGS_DIR}${MIDDLEWARE_ZIP_PATH}" \
             "${LOCAL_LOGS_DIR}"

cd ${LOCAL_LOGS_DIR} && unzip "*.zip"
