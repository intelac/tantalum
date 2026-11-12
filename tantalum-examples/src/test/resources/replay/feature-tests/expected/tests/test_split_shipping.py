import unittest

from inventory.orders import place_order
from inventory.reports import shipping_table
from inventory.shipping import compute_shipping_fee, split_fee
from inventory.stock import restock


class SplitFeeTest(unittest.TestCase):
    def test_single_batch_equals_normal_fee(self):
        self.assertEqual(split_fee(2, "local", 1), compute_shipping_fee(2, "local"))

    def test_multiple_batches(self):
        # 2 batches of 2kg: (5 + 2) * 2
        self.assertEqual(split_fee(4, "local", 2), 14)

    def test_invalid_batches(self):
        for bad in (0, -1):
            with self.assertRaises(ValueError):
                split_fee(2, "local", bad)

    def test_unknown_zone(self):
        with self.assertRaises(ValueError):
            split_fee(2, "moon", 2)


class OrderBatchesTest(unittest.TestCase):
    def test_default_and_batches(self):
        restock("SKU-0003", 10)  # weight 1.0 kg
        default = place_order("SKU-0003", 2, 10, "local")
        self.assertEqual(default["shipping"], 7)
        split = place_order("SKU-0003", 2, 10, "local", batches=2)
        self.assertEqual(split["shipping"], 12)
        self.assertEqual(split["total"], split["goods"] + 12)


class ReportBatchesTest(unittest.TestCase):
    def test_default(self):
        self.assertEqual(shipping_table([2], "local"), "   2.0 kg      7.00")

    def test_batches(self):
        self.assertEqual(shipping_table([4], "local", batches=2), "   4.0 kg     14.00")
