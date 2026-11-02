"""Reads the lab's MongoDB and prints what it finds, then shows write concern and read preference at work
(Stage 6, steps C8 and C11). Changes nothing it does not remove again.

Run from the python folder with the passwords from .env in the environment:
    python -m mongo_lab.report               (the local MongoDB)
    python -m mongo_lab.report --target atlas
"""

import argparse
import time

from pymongo import ReadPreference
from pymongo.write_concern import WriteConcern

from billing.domain import BillingPeriod
from mongo_lab import settings
from mongo_lab.bills import _dec
from mongo_lab.client import make_client
from mongo_lab.summary import account_summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Read the lab's MongoDB")
    parser.add_argument("--target", choices=["local", "atlas"], default=None)
    args = parser.parse_args()

    with make_client(settings.mongo_uri(args.target)) as client:      # the with-block closes the client's pool
        db = client.get_database("utility_lab")
        hello = db.command("hello")
        print(f"connected to MongoDB {db.command('buildInfo')['version']}, replica set {hello.get('setName')}")
        print(f"members: {', '.join(hello.get('hosts', []))}; primary: {hello.get('primary')}")

        summary = account_summary(db, 1)
        for p in summary["premises"]:
            for m in p["meters"]:
                print(f"customer 1, {p['address']}, {m['serial_number']}: latest {m['latest_read_at']} "
                      f"{_dec(m['latest_kwh'])} kWh")
        october = BillingPeriod(2026, 10)
        bucket = db.meter_readings.find_one({"meter_id": 1, "month": "2026-10"}, {"total_kwh": 1, "reading_count": 1})
        if bucket is not None:
            print(f"meter 1, {october.first_day:%B %Y}: {bucket['reading_count']} readings, "
                  f"{_dec(bucket['total_kwh'])} kWh")

        # Write concern (lesson A4): the same 20 inserts, confirmed by the primary alone, then by a majority.
        scratch = db.get_collection("stage6_scratch")
        scratch.insert_one({"warm-up": True})        # the first write also opens connections: not timed
        for label, concern in [("w=1       ", WriteConcern(w=1)), ("w=majority", WriteConcern(w="majority"))]:
            coll = scratch.with_options(write_concern=concern)
            start = time.perf_counter()
            for i in range(20):
                coll.insert_one({"i": i})
            print(f"20 inserts, {label}: {(time.perf_counter() - start) * 1000:.0f} ms")
        scratch.drop()

        # Read preference (lesson A4): which member answers a read.
        for label, preference in [("primary           ", ReadPreference.PRIMARY),
                                  ("secondaryPreferred", ReadPreference.SECONDARY_PREFERRED)]:
            coll = db.get_collection("customers", read_preference=preference)
            cursor = coll.find({"_id": 1}, {"_id": 1})
            cursor.next()
            host, port = cursor.address or ("?", 0)
            print(f"read with {label} answered by {host}:{port}")


if __name__ == "__main__":
    main()