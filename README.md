# ASL fall project 2018

## Repository structure
```bash
 .
    ├── middleware      # Source files and .jar
    ├── experiments     # Log files, processing scripts, plots, images
    ├── report          # Latex source and .pdf
    └── README.md       # this file

  experiments
    ├── baseline_no_mw_1_server      # 2.1
          ├── parse_memtier.ipynb    # ipython notebook that parses MW e client logs
          ├── logs                   # logs directory
                └── timestamp_dir    # timestamp directory (inside all logs and configs)
          ├── out                    # processed log files
          └── img                    # plots w
    ├── baseline_no_mw_2_server      # 2.2
    ├── baseline_mw_1_mw             # 3.1
    ├── baseline_mw_2_mw             # 3.2
    ├── throughput_for_writes        # 4.1
    ├── multigets_sharded            # 5.1
    ├── multigets_non_sharded        # 5.2
    ├── 2k_analysis                  # 6
    └── queue_models                 # 7
```
Every log file has a clear structure in its name that identifies the configuration that is being tested, e.g.
```
ratio1:0_run2_vclients1_workerthreads16_client3_instance1.log
```
means that this log represents the 2nd repetition of a write-only workload, with 1 vclient, 16 workerthreads and is being sent from the instance 1 of client 3.

