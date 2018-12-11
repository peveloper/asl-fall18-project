#!/usr/bin/env python3

import scipy.stats as stats
import statsmodels.api as sm
import matplotlib.pyplot as plt
import numpy as np


values = [
    ('read_only',
        [
            -4.0, 1.2, 2.8, -1.4, 1.5, -0.1, -1.3, -2.1, 3.5, -5.3, -0.7,
            6.0, -5.7, -16.2, 21.8, 9.4, -6.7, -2.7, 56.7, 27.2, -83.9, -6.7,
            -5.3, 21.9
        ]
    ),

    ('write_only',
        [
            10.0, -33.0, 23.1, -86.3, 94.1, -7.8, -356.0, -41.1, 397.1,
            -86.4, 182.1, -95.8, 94.2, -59.1, -35.1, -105.3, -20.8, 126.1,
            35.7, -3.7, -31.9, -69.6, 294.0, -224.4
        ]
    )
]

for filename, errors in values:
    errors = sorted(errors)
    sm.qqplot(np.array(errors), line='s')
    plt.savefig('img/tp_' + filename + '_qq.png', bbox_inches='tight')
