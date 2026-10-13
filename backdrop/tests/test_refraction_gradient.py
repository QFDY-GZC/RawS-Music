"""CPU numerical regression for the production AGSL gradient.

Run: python -m unittest discover -s backdrop/tests -v
Requires numpy. This evaluates the actual function body, not a copied formula;
GPU compilation and visual rendering still require Android verification.
"""
import math
from pathlib import Path
import re
import unittest

import numpy as np


def production_gradient():
    source = (Path(__file__).parents[1] / "src/main/java/com/kyant/backdrop/Shaders.kt").read_text(encoding="utf-8")
    start = source.index("float2 gradSdRoundedRect(")
    opening = source.index("{", start)
    depth = 1
    closing = opening + 1
    while depth:
        depth += (source[closing] == "{") - (source[closing] == "}")
        closing += 1
    body = source[opening + 1:closing - 1]
    # Translate only this small arithmetic AGSL subset. Unsupported syntax fails
    # compilation rather than silently substituting a test-only implementation.
    body = re.sub(r"//[^\n]*", "", body)
    body = re.sub(r"\b(float2|float)\s+(\w+)\s*=", r"\2 =", body)
    body = re.sub(r"\b(\w+)\.([xy])\b", lambda m: m[1] + ("[0]" if m[2] == "x" else "[1]"), body)
    body = body.replace("||", " or ").replace("&&", " and ")
    lines = ["def gradient(coord, halfSize, radius):"]
    indent = 1
    for token in re.split(r"([{};])", body):
        token = token.strip()
        if not token or token == ";":
            continue
        if token == "}":
            indent -= 1
        elif token == "{":
            indent += 1
        else:
            if token.startswith("if ("):
                token = "if " + token[4:-1] + ":"
            elif token == "else":
                token = "else:"
            lines.append("    " * indent + token)
    def normalize(vector):
        with np.errstate(divide="ignore", invalid="ignore"):
            return vector / np.linalg.norm(vector)
    namespace = dict(abs=np.abs, max=np.maximum, sign=np.sign,
                     normalize=normalize, step=lambda edge, value: float(value >= edge),
                     float2=lambda *args: np.array(args if len(args) == 2 else args * 2, dtype=float))
    exec("\n".join(lines), namespace)
    return namespace["gradient"]


class RefractionGradientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gradient = staticmethod(production_gradient())

    def evaluate(self, x, y):
        # 18dp at 560dpi -> 63px; production gradient radius is 94.5px.
        return self.gradient(np.array([x, y]), np.array([564., 875.]), 94.5)

    def test_inner_straight_edges_have_finite_outward_normals(self):
        for point, expected in [((469.5, 0), (1, 0)), ((-469.5, 0), (-1, 0)),
                                ((0, 780.5), (0, 1)), ((0, -780.5), (0, -1))]:
            with self.subTest(point=point):
                np.testing.assert_allclose(self.evaluate(*point), expected)

    def test_exact_corner_centers_are_finite_unit_vectors(self):
        for x in [-469.5, 469.5]:
            for y in [-780.5, 780.5]:
                with self.subTest(x=x, y=y):
                    result = self.evaluate(x, y)
                    self.assertTrue(np.isfinite(result).all())
                    self.assertAlmostEqual(np.linalg.norm(result), 1.)

    def test_straight_edge_crossing_stays_continuous(self):
        for delta in [-0.001, 0., 0.001]:
            np.testing.assert_allclose(self.evaluate(469.5 + delta, 100), (1, 0))

    def test_rounded_corner_direction_is_preserved(self):
        diagonal = 1 / math.sqrt(2)
        np.testing.assert_allclose(self.evaluate(500, 811), (diagonal, diagonal))


if __name__ == "__main__":
    unittest.main()
