import unittest

from inventory.pricing import member_discount


class MemberDiscountTest(unittest.TestCase):
    def test_normal(self):
        self.assertEqual(member_discount(100, "普通"), 100)

    def test_silver(self):
        self.assertEqual(member_discount(200, "白银"), 190)

    def test_gold(self):
        self.assertEqual(member_discount(200, "黄金"), 180)

    def test_rounding(self):
        self.assertEqual(member_discount(10.99, "黄金"), 9.89)

    def test_unknown_level(self):
        with self.assertRaises(ValueError):
            member_discount(100, "钻石")


if __name__ == "__main__":
    unittest.main()
