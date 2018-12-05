#!/usr/bin/env python3

import scipy.stats as stats
import statsmodels.api as sm
import matplotlib.pyplot as plt
import numpy as np


stuff = [
    ('read_only',
        [
           [-4.0, 1.2, 2.8],
           [-1.4, 1.5, -0.1],
           [-1.3, -2.1, 3.5],
           [-5.3, -0.7, 6.0],
           [-5.7, -16.2, 21.8],
           [9.4, -6.7, -2.7],
           [56.7, 27.2, -83.9],
           [-16.7, -5.3, 21.9]
        ]
    ),

    ('write_only',
       [
           [10.0, -33.0, 23.1],
           [-86.3, 94.1, -7.8],
           [-356.0, -41.1, 397.1],
           [-86.4, 182.1, -95.8],
           [94.2, -59.1, -35.1],
           [-105.3, -20.8, 126.1],
           [35.7, -3.7, -31.9],
           [-69.6, 294.0, -224.5]
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
    plt.savefig('out/tp_' + filename + '_residuals.png', bbox_inches='tight')
