SELECT x, SUM(z) AS zs FROM t1 GROUP BY x ORDER BY ABS(zs)
