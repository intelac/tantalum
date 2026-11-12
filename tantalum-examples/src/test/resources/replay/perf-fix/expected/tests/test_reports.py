import time
import unittest

from inventory.reports import find_duplicates


class FindDuplicatesTest(unittest.TestCase):
    def test_order_is_by_first_repeat(self):
        self.assertEqual(find_duplicates(["a", "b", "b", "a", "c", "a", "c"]), ["b", "a", "c"])

    def test_no_duplicates(self):
        self.assertEqual(find_duplicates(["a", "b"]), [])

    def test_large_input_is_fast(self):
        data = [str(i % 50000) for i in range(100000)]
        start = time.time()
        result = find_duplicates(data)
        self.assertLess(time.time() - start, 2)
        self.assertEqual(len(result), 50000)
        self.assertEqual(result[:3], ["0", "1", "2"])


if __name__ == "__main__":
    unittest.main()
