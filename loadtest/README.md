# loadtest

k6 profiles (SDD 19), chaos scripts and the report generator.

**Outside the Maven reactor** by design (DD-005): binding the k6/npm lifecycle to
Maven phases couples unrelated builds and defeats Docker layer caching for both.

Populated in Phase 1c. AC-0.7's calibration ramp lands here first.
