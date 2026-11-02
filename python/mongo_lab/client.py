"""One configured MongoClient for the whole program (Stage 6, lessons A2 and A4).

A MongoClient is the counterpart of a connection pool, not of one connection: it keeps a pool of connections to
every member of the replica set, watches which member is primary, and is safe to share between threads. A
program makes one when it starts and closes it when it stops, as the Java program's HikariCP pool.
"""

from typing import Any

from pymongo import MongoClient
from pymongo.write_concern import WriteConcern

# The type of every document in the lab's code: a dictionary from field names to values.
Doc = dict[str, Any]


def make_client(uri: str, **options: Any) -> MongoClient[Doc]:
    """A MongoClient with the settings the lab relies on; options passed in replace them (the tests use that).

    - appname: shows in the server's logs and in Atlas's monitoring, to tell programs apart.
    - tz_aware=True: dates come back as datetime objects that carry their time zone (UTC), never as "naive"
      datetimes that Python would take for local time (lesson A2).
    - w="majority": a write is confirmed only once most members of the replica set hold it (lesson A4).
    - retryWrites=True: the driver repeats a write once if the primary changes underneath it (lesson A4).
      It is the default; it is written here so that the setting is visible.
    - serverSelectionTimeoutMS=5000: give up after 5 seconds if no suitable server can be found, instead of 30.
    """
    settings: dict[str, Any] = {
        "appname": "db-lab-python",
        "tz_aware": True,
        "w": "majority",
        "retryWrites": True,
        "serverSelectionTimeoutMS": 5000,
    }
    settings.update(options)
    return MongoClient(uri, **settings)


def majority() -> WriteConcern:
    """The write concern used for bills: majority, and written to the journal (the counterpart of a commit)."""
    return WriteConcern(w="majority", j=True)