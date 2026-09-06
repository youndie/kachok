# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal
# state of affairs, and people stop reading either. So: whatever is not in `make check` is not a
# gate, and whatever is in it runs the same way in both places.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation and not a matter of opinion.
gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)
	@# The application icon is generated from B-86's geometry, so a committed file that no longer
	@# matches it is a defect. `.icns` is built by `iconutil`, which is macOS only — on the Linux
	@# runner the script writes the other two and compares those, and the container nobody can
	@# rebuild there is checked on the mac. Both machines run the same command.
	$(PY) scripts/make_icon.py --check
	@# The UI's goldens, on the machine that recorded them. They are a gate — a screen that stopped
	@# looking like the design is a defect — and they cannot run on the Linux build machine, whose
	@# rasteriser is not the one the pictures came from. `LOCAL=1` is the prefer-wsl hook's own
	@# escape for exactly this: a target that must run here.
	@if [ -d ui/src/desktopTest/snapshots ]; then LOCAL=1 ./gradlew --quiet --console=plain :ui:viddikVerify; fi

# Non-blocking, on purpose. bdd_report counts scenarios; demanding a percentage is meaningless while
# acceptance is done by hand. code_anchors cannot tell a live path from one quoted as a target that
# the backlog has not built yet. Both are read by a person.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
