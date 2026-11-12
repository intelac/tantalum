import unittest

from inventory.reports import shipping_table


class ReportsTest(unittest.TestCase):
    def test_one_row_per_weight(self):
        self.assertEqual(len(shipping_table([1.0, 2.0, 3.5], "local").splitlines()), 3)

    def test_two_decimals(self):
        self.assertIn("6.00", shipping_table([1.0], "local"))
