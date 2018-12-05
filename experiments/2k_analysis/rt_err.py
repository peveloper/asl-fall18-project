#!/usr/bin/env python3

import scipy.stats as stats
import statsmodels.api as sm
import matplotlib.pyplot as plt
import numpy as np


values = [
    ('read_only',
        [
            0.1, -0.1, 0.0, 0.2, 0.1, -0.3, 0.1, 0.0, -0.1, 0.1, 0.0,
            -0.1, 0.4, 1.7, -2.1, -0.7, -0.7, -0.1, -1.6, -1.8, 3.4, 0.2,
            0.6, -0.8
        ]
    ),

    ('write_only',
        [
            -0.8, 1.1, -0.4, 0.7, -1.6, 0.9, 0.7, 0.2, -0.9,
            0.1, -0.1, 0.0, 1.6, -1.3, -0.3, -2.0, 0.7, 1.3,
            -0.1, -0.1, 0.2, 0.0, -0.4, 0.4
        ]
    )
]

for filename, errors in values:
    errors = sorted(errors)
    sm.qqplot(np.array(errors), line='s')
    plt.savefig('out/rt_' + filename + '_qq.png', bbox_inches='tight')
