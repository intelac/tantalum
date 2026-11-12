import io
import unittest
from contextlib import redirect_stdout

from inventory.cli import main


class CliOrderTest(unittest.TestCase):
    def test_order_quantity_from_argv_is_a_string(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(["order", "SKU-0003", "3", "national"])
        self.assertEqual(code, 0)
        self.assertTrue(out.getvalue().strip())


if __name__ == "__main__":
    unittest.main()
