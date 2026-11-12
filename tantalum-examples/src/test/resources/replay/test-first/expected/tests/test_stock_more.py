import unittest

from inventory.stock import reserve_stock, restock


class StockMoreTest(unittest.TestCase):
    def test_restock_returns_new_level(self):
        self.assertEqual(restock("SKU-0010", 4), 4)

    def test_restock_accumulates(self):
        restock("SKU-0011", 3)
        self.assertEqual(restock("SKU-0011", 2), 5)

    def test_reserve_normal(self):
        restock("SKU-0012", 5)
        self.assertEqual(reserve_stock("SKU-0012", 5), 0)

    def test_reserve_not_enough(self):
        restock("SKU-0013", 2)
        with self.assertRaises(ValueError):
            reserve_stock("SKU-0013", 3)
        # level unchanged after failure
        self.assertEqual(reserve_stock("SKU-0013", 2), 0)

    def test_reserve_without_stock(self):
        with self.assertRaises(ValueError):
            reserve_stock("SKU-0014", 1)


if __name__ == "__main__":
    unittest.main()
