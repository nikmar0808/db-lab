"""The account summary from MongoDB: one customer, their premises and meters, each meter's latest reading
(Stage 6, lesson A6). Two commands, however many premises and meters: the counterpart of Stage 4's "one query
per level", here because the customer document already holds its premises and meters.
"""

from typing import Any

from pymongo.database import Database

from mongo_lab.client import Doc


def account_summary(db: Database[Doc], customer_id: int) -> dict[str, Any]:
    customer = db.customers.find_one({"_id": customer_id})               # command 1: the whole account
    if customer is None:
        raise LookupError(f"customer {customer_id} does not exist")
    meter_ids = [m["meter_id"] for p in customer["premises"] for m in p["meters"]]
    # Command 2: each meter's latest reading, for all the meters at once. Newest bucket first, then the last
    # element of its list ($last), then the first such row per meter ($first after the sort).
    latest = {row["_id"]: row for row in db.meter_readings.aggregate([
        {"$match": {"meter_id": {"$in": meter_ids}}},
        {"$sort": {"meter_id": 1, "month": -1}},
        {"$group": {"_id": "$meter_id", "reading": {"$first": {"$last": "$readings"}}}},
    ])}
    return {
        "customer_id": customer["_id"], "name": customer["name"],
        "premises": [{
            "premise_id": p["premise_id"], "address": p["address"],
            "meters": [{
                "meter_id": m["meter_id"], "serial_number": m["serial_number"],
                "latest_read_at": latest.get(m["meter_id"], {}).get("reading", {}).get("read_at"),
                "latest_kwh": latest.get(m["meter_id"], {}).get("reading", {}).get("kwh"),
            } for m in p["meters"]],
        } for p in customer["premises"]],
    }