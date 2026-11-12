import unittest

from inventory.reservations import reserve_stock
from inventory.stock import restock


class StockTest(unittest.TestCase):
    def test_reserve(self):
        restock("SKU-0001", 5)
        self.assertEqual(reserve_stock("SKU-0001", 2), 3)
