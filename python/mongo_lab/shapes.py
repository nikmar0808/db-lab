"""Bills of two shapes, side by side (Stage 6, lesson A7).

Version 1 is what Stage 5's report wrote with $merge: an amount, a kWh total, no lines and no schema_version.
Version 2 is what bills.produce_bill writes: schema_version 2 and a list of lines. Old documents are not changed
by a release; the code reads both, and upgrade_v1_bills can bring the old ones forward when convenient.
"""

from decimal import Decimal
from typing import Any

from bson.decimal128 import Decimal128
from pymongo import UpdateOne
from pymongo.database import Database

from mongo_lab.bills import _dec, premise_and_tariff
from mongo_lab.client import Doc


def schema_version(bill: Doc) -> int:
    """A document written before versions existed has no schema_version field: that is version 1."""
    version: int = bill.get("schema_version", 1)
    return version


def bill_lines(db: Database[Doc], bill: Doc) -> list[dict[str, Any]]:
    """The lines of a bill, whatever its version: read from version 2, worked out for version 1."""
    if schema_version(bill) >= 2:
        return [{**line, "amount": _dec(line["amount"])} for line in bill["lines"]]
    # Version 1: work the lines out from the kWh, the tariff and the number of days.
    _, _, tariff = premise_and_tariff(db, bill["premise_id"])
    kwh, rate, standing = _dec(bill.get("kwh")), _dec(tariff["rate_per_kwh"]), _dec(tariff["standing_charge"])
    days = (bill["period_end"] - bill["period_start"]).days + 1
    return [
        {"kind": "energy", "quantity": kwh, "rate": rate, "amount": (kwh * rate).quantize(Decimal("0.01"))},
        {"kind": "standing charge", "quantity": days, "rate": standing, "amount": standing * days},
    ]


def upgrade_v1_bills(db: Database[Doc]) -> int:
    """Rewrites every version 1 bill as version 2, in bulk; returns how many were changed. Safe to run again.

    The filter matches only documents without schema_version, so a second run finds nothing to do.
    """
    operations = []
    for bill in db.bills.find({"schema_version": {"$exists": False}}):
        lines = bill_lines(db, bill)
        stored = [{**line,
                   "quantity": Decimal128(line["quantity"]) if isinstance(line["quantity"], Decimal) else line["quantity"],
                   "rate": Decimal128(line["rate"]), "amount": Decimal128(line["amount"])} for line in lines]
        operations.append(UpdateOne({"_id": bill["_id"], "schema_version": {"$exists": False}},
                                    {"$set": {"schema_version": 2, "lines": stored}}))
    if not operations:
        return 0
    return db.bills.bulk_write(operations, ordered=False).modified_count