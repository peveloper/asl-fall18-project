import sys,time
import subprocess

from pssh.clients import ParallelSSHClient
from gevent import joinall
from pssh.exceptions import SessionError
from pssh.utils import enable_host_logger
enable_host_logger()

import os 
dir_path = os.path.dirname(os.path.realpath(__file__))


def exec_parallel(msg, client, command, keys=[], values=[], delimiter=None, sudo=True):
    
    args = [{} for i in range(len(keys))]
    for host in range(0, len(keys)):
        for k,v in zip(keys[host], values[host]):
            args[host][k] = v

    print(msg.upper())

    output = None
    if ">" in command:
        s = command.split(">", 1)
        command = s[0].strip()
        output = s[1].strip()

    stderr = None 

    if len(args) > 0: 
        for k in args[0]:
            command += ' ' + k + delimiter + ("%%(%s)s" % k)

    if output:
        if stderr:
            command += ' ' + stderr + '> ' + output
        else:
            command += ' > ' + output

    try :
        output = client.run_command(command, host_args=args, sudo=sudo)
    except SessionError:
        hosts = client.hosts
        client = ParallelSSHClient(hosts)
        output = client.run_command(command, host_args=args, sudo=sudo)

    client.join(output, consume_output=True)

    return client



def copy_parallel(msg, client, remote_location, local_location, test=True):
    print(msg.upper())
    
    try :
        greenlets = client.copy_remote_file(remote_location, local_location)
    except SessionError:
        hosts = client.hosts
        client = ParallelSSHClient(hosts)
        greenlets = client.copy_remote_file(remote_location, local_location)

    joinall(greenlets, raise_error=True)
    return client

def main(argv):

    servers = 3
    clients = 3 
    middlewares = 2
    instances = 2 
    threads = 1 
    virtual_clients = [1, 2, 4, 8, 16, 32, 64]
    worker_threads = [8, 16, 32, 64]
    sharded = "false"
    ratios = ["1:0", "0:1"]
    runs = 3 
    hostnames = ["pstefanoforaslvms%d.westeurope.cloudapp.azure.com" % x for x in range(1, 9)]
    private_ips = [
        "10.0.0.8",
        "10.0.0.9",
        "10.0.0.6",
        "10.0.0.11",
        "10.0.0.7",
        "10.0.0.10",
        "10.0.0.5",
        "10.0.0.4"
    ]
    memcached_port = "11211"
    middleware_port = "8888"
    key_maximum = "10000"
    test_time = "80"
    data_size = "4096"
    protocol = "memcache_text"
    write_ratio = "1:0"
    key_pattern = "S:S"
    expiry_range = "9999-10000"

    server_hostnames = hostnames[:servers]
    client_hostnames = hostnames[5:5+clients]
    middleware_hostnames = hostnames[3:3+middlewares]

    server_ips = private_ips[:servers]
    middleware_ips = private_ips[3:3+middlewares]

    memcached_clients = ParallelSSHClient(server_hostnames) 
    memtier_clients = ParallelSSHClient(client_hostnames)
    single_memtier_client = ParallelSSHClient([client_hostnames[0]])
    middleware_clients = ParallelSSHClient(middleware_hostnames)

    process = subprocess.Popen("date +%Y%m%d%H%M%S".split(), stdout=subprocess.PIPE)
    output, error = process.communicate()

    timestamp = output.decode('UTF-8').split('\n')[0]

    process = subprocess.Popen(("mkdir -p logs/%s" % timestamp).split(), stdout=subprocess.PIPE)
    time.sleep(2)
    process = subprocess.Popen(("chmod -R 777 logs/").split(), stdout=subprocess.PIPE)

    log_dir = "logs/throughput_for_writes/%s/" % timestamp
    middleware_log_dir = "logs/"
    middleware_jar = "asl/middleware/dist/middleware-pstefano.jar"

    print(middleware_log_dir)
    print(middleware_jar)
    print(log_dir)
    print(dir_path)

    exec_parallel("Killing memcached\n", memcached_clients, "killall memcached")

    exec_parallel("Killing middleware\n", middleware_clients, "pkill -f '^java.*'")

    exec_parallel("Removing old logs\n", memtier_clients, "rm -rf %s" % log_dir.replace("/%s/" % timestamp, ""))

    keys = [["-p"] for s in range(len(client_hostnames))]
    values = [[log_dir] * len(keys[0])] * len(keys)
    exec_parallel("Setting up log directories on clients\n", memtier_clients, "mkdir", keys, values, ' ')

    exec_parallel("Changing permissions of log directories on clients\n", memtier_clients, "chmod -R 777 % s" % log_dir)

    memtier_clients.hosts = memtier_clients.hosts * instances

    for ratio in ratios:
        for run in range(0, runs):

            keys = [["-p", "-t"] for s in range(len(server_ips))]
            values = [[memcached_port, "1"] * len(keys[0])] * len(keys)
            exec_parallel("Starting memcached\n", memcached_clients, "memcached > /dev/null &", keys, values, ' ', False)

            if ratio != "1:0":
                keys = [[
                    "--server",
                    "--port",
                    "--protocol",
                    "--threads",
                    "--ratio",
                    "--clients",
                    "--key-maximum",
                    "--key-pattern",
                    "--data-size",
                    "--expiry-range"
                ] for s in range(len(server_ips))]

                values = [["%s"] * len(keys[0]) ] * len(keys)

                for s in range(len(server_ips)):
                    values[s] = [
                        server_ips[s],
                        memcached_port,
                        protocol,
                        str(2),
                        write_ratio,
                        str(16),
                        key_maximum,
                        key_pattern,
                        data_size,
                        expiry_range
                    ]                        

                # Populate from one client machine [0]
                exec_parallel("Populating keys\n", single_memtier_client, "memtier_benchmark > /dev/null", keys, values, '=')

            for wk in worker_threads:

                keys = [[
                    "-l",
                    "-p",
                    "-t",
                    "-s",
                    "-m"
                ] for m in range(len(middleware_clients.hosts))]

                values = [["%s"] * len(keys[0]) ] * len(keys)

                for mw in range(len(middleware_clients.hosts)):
                    values[mw] = [
                        middleware_ips[mw],
                        middleware_port,
                        str(wk),
                        sharded,
                        (':%s' % middleware_port).join(server_ips) + ":%s" % middleware_port
                    ]

                # Start middleware
                exec_parallel("Starting Middleware/s\n", middleware_clients, "java -jar %s > /dev/null &" % middleware_jar, keys, values, ' ')

                exec_parallel("Waiting for middleware/s to have started\n", middleware_clients, "while pgrep -f '^java.*' > /dev/null; [ $? -ne 0 ]; do sleep 1; done;")

                time.sleep(8)

                # Start benchmarks
                for vc in virtual_clients:
                    keys = [[
                        "--server",
                        "--port",
                        "--protocol",
                        "--threads",
                        "--ratio",
                        "--clients",
                        "--key-maximum",
                        "--data-size",
                        "--test-time",
                        "--json-out-file"
                    ] for s in range(len(memtier_clients.hosts))]

                    values = [["%s"] * len(keys[0]) ] * len(keys)

                    middleware_log_name = "ratio_%s_clients_%d_wk_%d_run_%d" % (ratio, vc, wk, run)

                    for host in range(len(memtier_clients.hosts)):

                        instance = int(host / clients)

                        memtier_log_name = "%sratio_%s_clients_%d_wk_%d_instance_%d_run_%d" % (log_dir, ratio, vc, wk, instance, run)

                        values[host] = [
                            middleware_ips[instance],
                            middleware_port,
                            protocol,
                            str(threads),
                            ratio,
                            str(vc),
                            str(int(key_maximum) - 1),
                            data_size,
                            test_time,
                            memtier_log_name
                        ]                        

                    # Execute on each instance
                    exec_parallel("", memtier_clients, "memtier_benchmark", keys, values, '=')

                    # Wait for memtier_clients to be done..
                    exec_parallel("Waiting for benchmarks to finish\n", memtier_clients, "while pgrep -f '^memtier_benchmark.*' > /dev/null; do sleep 1; done;")

                    # Kill middleware
                    exec_parallel("Killing middleware\n", middleware_clients, "pkill -f '^java.*'")

                    # Wait for middleware_clients to be done..
                    exec_parallel("Waiting for middlewares to finish\n", middleware_clients, "while pgrep -f '^java.*' > /dev/null; do sleep 1; done;")

                    # Rename middleware logs
                    exec_parallel("Renaming middleware logs\n", middleware_clients, "cd %s && cp stats.log stats_%s && cp middleware.log middleware_%s" % (middleware_log_dir, middleware_log_name, middleware_log_name))

            # Shutdown memcached 
            exec_parallel("Killing memcached\n", memcached_clients, "killall memcached")

    memtier_clients.hosts = client_hostnames

    splitted_log_path = log_dir.split('/')
    print(splitted_log_path)
    remote_zip_log = '-'.join(splitted_log_path[1:-2])
    print(remote_zip_log)
    local_zip_log = "logs/" + '/'.join(splitted_log_path[-2:-1])
    print(local_zip_log)

    # Remove client old zip
    exec_parallel("Removing old zip\n", memtier_clients, "rm -rf *.zip")

    # Zip client logs
    exec_parallel("Zipping Clients Logs\n", memtier_clients, "zip -r %s.zip %s" % (remote_zip_log, log_dir))

    # Copy client logs 
    copy_parallel("Copying Clients Logs\n", memtier_clients, "%s.zip" % remote_zip_log, "%s/%s/%s.zip" % (dir_path, local_zip_log, remote_zip_log))

    # Remove middleware old zip
    exec_parallel("Removing old zip\n", middleware_clients, "rm -rf *.zip")

    # Zip middleware logs
    exec_parallel("Zipping Middleware Logs\n", middleware_clients, "zip -r %s.zip logs/" % remote_zip_log)

    # Copy middleware logs 
    copy_parallel("Copying Middleware Logs\n", middleware_clients, "%s.zip" % remote_zip_log, "%s/%s/%s.zip" % (dir_path, local_zip_log, remote_zip_log))

    # Rename zip files
    for filename in os.listdir(local_zip_log):
        print(local_zip_log)
        if "s4" in filename:
            os.rename(os.path.join(local_zip_log, filename), os.path.join(local_zip_log + "/middleware_00.zip"))
        elif "s5" in filename:
            os.rename(os.path.join(local_zip_log, filename), os.path.join(local_zip_log + "/middleware_01.zip"))
        elif "s6" in filename:
            os.rename(os.path.join(local_zip_log, filename), os.path.join(local_zip_log + "/client_00.zip"))
        elif "s7" in filename:
            os.rename(os.path.join(local_zip_log, filename), os.path.join(local_zip_log + "/client_01.zip"))
        elif "s8" in filename:
            os.rename(os.path.join(local_zip_log, filename), os.path.join(local_zip_log + "/client_10.zip"))


if __name__ == '__main__':
    main(sys.argv[1:])
