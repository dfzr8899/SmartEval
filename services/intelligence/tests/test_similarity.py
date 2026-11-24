import unittest

from smarteval_ai.similarity import SimilarityRequest, similarity


# Maximum contiguous subarray sum for a nonempty array.
KADANE = """def max_sum(numbers):
    ending = best = numbers[0]
    for number in numbers[1:]:
        ending = max(number, ending + number)
        best = max(best, ending)
    return best
"""
RENAMED = """# Maximum subarray
def solve(xs):
    tail = answer = xs[0]

    for x in xs[1:]:
        tail = max(x, tail + x)  # extend or restart
        answer = max(answer, tail)

    return answer
"""
LOCAL_REWRITE = KADANE.replace("        ending = max(number, ending + number)",
                               "        extended = ending + number\n        ending = max(extended, number)")
# A different O(n²) algorithm enumerates starting positions and extends each interval.
ENUMERATE = """def brute(xs):
    best = float("-inf")
    for start in range(len(xs)):
        subtotal = 0
        for right in range(start, len(xs)):
            subtotal += xs[right]
            if subtotal > best:
                best = subtotal
    return best
"""


def compare_pair(left, right, language="PYTHON"):
    request = SimilarityRequest(submissions=[
        {"submission_id": "left", "language": language, "text": left},
        {"submission_id": "right", "language": language, "text": right},
    ])
    return similarity(request)["pairs"][0]


class SimilarityTest(unittest.TestCase):
    def test_controls_have_distinct_scores_and_original_lines(self):
        a, b, c = [compare_pair(KADANE, variant) for variant in (RENAMED, LOCAL_REWRITE, ENUMERATE)]
        self.assertEqual(a["score"], 1.0)
        self.assertEqual(a["matches"][0]["left_lines"], {"start": 1, "end": 6})
        self.assertEqual(a["matches"][0]["right_lines"], {"start": 2, "end": 9})
        self.assertGreater(a["score"], b["score"])
        self.assertGreater(b["score"] - c["score"], 0.30)
        self.assertEqual(a, compare_pair(KADANE, RENAMED))

    def test_official_grammars_normalize_identifiers_literals_and_comments(self):
        for language, code in {
            "JAVA": "class Demo { int solve(int x) { if (x > 3) return x + 7; return x - 1; } }",
            "CPP": "int solve(int x) { if (x > 3) return x + 7; return x - 1; }",
            "C": "int solve(int x) { if (x > 3) return x + 7; return x - 1; }",
            "JAVASCRIPT": "function solve(x) { if (x > 3) return x + 7; return x - 1; }",
        }.items():
            with self.subTest(language=language):
                changed = "// header\n" + code.replace("solve", "work").replace("x", "y").replace("3", "4")
                pair = compare_pair(code,changed,language)
                self.assertEqual(pair["score"], 1.0)
                self.assertEqual(pair["matches"][0]["right_lines"], {"start": 2, "end": 2})

    def test_swapped_functions_preserve_matched_line_ranges(self):
        first = """def helper(arr):
    total = 0
    for v in arr:
        if v > 0:
            total += v
    return total

def solver(nums):
    best = nums[0]
    for n in nums[1:]:
        best = max(best, n)
    return best
"""
        swapped = """def solver(xs):
    ans = xs[0]
    for x in xs[1:]:
        ans = max(ans, x)
    return ans

def helper(items):
    acc = 0
    for item in items:
        if item > 0:
            acc += item
    return acc
"""
        pair = compare_pair(first, swapped, "PYTHON")
        self.assertGreater(pair["score"], 0.85)
        self.assertGreaterEqual(len(pair["matches"]), 2)
