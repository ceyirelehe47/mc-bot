# Reproduce the independent offline review

This archive is an audit artifact, not an implementation patch or a Minecraft task package.
All synthetic inputs are deliberately invalid evidence. They must never be used as gameplay results.

## Python

    python run_counterexamples.py

Expected on this committed checker: seven invalid probes are accepted, three rejection controls are rejected.
The probe exits 1 while an invalid input is accepted. It verifies the committed checker Git blob first.

## Java 21

    mkdir classes
    javac -d classes RealClientEatDecisionCore.java EatCoreProbe.java
    java -cp classes EatCoreProbe

The production class is byte-identical to the reviewed Git blob. EatCoreProbe injects synthetic observations into the pure production core; no Minecraft or server is launched.

PROVENANCE.json and REVIEW.zh-CN.md explain scope, sources and limitations.
