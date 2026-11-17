"""RecruitPro simulation runner.

Drives a running RecruitPro stack end to end with a generated resume corpus (``tools/resumelab``): creates the
corpus jobs as HR, registers one candidate per resume, uploads, waits for analysis, applies, waits for matching,
then scores the HR ranking against the corpus gold labels. See ``README.md``.
"""

__version__ = "0.1.0"
