#!/usr/bin/env python3

import scipy.stats as stats
import statsmodels.api as sm
import matplotlib.pyplot as plt
import numpy as np


stuff = [
    ('read_only',
        [
           [0.1, -0.1, 0.0],
           [0.2, 0.1, -0.3],
           [0.1, 0.0, -0.1],
           [0.1, -0.0, -0.1],
           [0.4, 1.7, -2.1],
           [-0.7, 0.7, -0.1],
           [-1.6, -1.8, 3.4],
           [0.2, 0.6, -0.8]
        ]
    ),

    ('write_only',
       [
           [-0.8, 1.1, -0.4],
           [0.7, -1.6, 0.9],
           [0.7, 0.2, -0.9],
           [0.1, -0.1, 0.0],
           [1.6, -1.3, -0.3],
           [-2.0, 0.7, 1.3],
           [-0.1, -0.1, 0.2],
           [0.0, -0.4, 0.4]
        ]
     )
]

for filename, values in stuff:

    fig, ax = plt.subplots()
    plt.xlabel("Combination")
    plt.ylabel("Residuals")

    index = 1
    for tps in values:

        plt.scatter([index] * len(tps), tps)
        index += 1
        ax.set_xlim(xmin=0, xmax=9)

    plt.xticks(range(1, 9), ["i=" + str(i) for i in range(1, 9)])
    plt.savefig('out/rt_' + filename + '_residuals.png', bbox_inches='tight')
