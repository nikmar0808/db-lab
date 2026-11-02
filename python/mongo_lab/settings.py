"""Where the lab's MongoDB is, and who connects (Stage 6, lesson A3).

Two targets, chosen by configuration: "local" (the lab's container, the default) and "atlas" (the free cluster
of Stage 5). The code that reads and writes data is the same for both; only the address changes.
"""

import os
from urllib.parse import quote_plus


def _require(name: str) -> str:
    """The value of an environment variable that must be set."""
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"environment variable {name} is not set: load .env first")
    return value


def mongo_uri(target: str | None = None) -> str:
    """The connection address of the chosen target, with lab_app's password from the environment.

    target is "local" or "atlas"; when it is None, the environment variable MONGO_TARGET decides, and "local" is
    the default. quote_plus makes the password safe inside an address, should it contain characters such as @.
    """
    target = target or os.environ.get("MONGO_TARGET", "local")
    if target == "local":
        host = os.environ.get("MONGO_HOST", "127.0.0.1:27017")
        password = quote_plus(_require("MONGO_APP_PASSWORD"))
        return f"mongodb://lab_app:{password}@{host}/utility_lab?directConnection=true&authSource=admin"
    if target == "atlas":
        host = _require("ATLAS_HOST")
        password = quote_plus(_require("ATLAS_APP_PASSWORD"))
        return f"mongodb+srv://lab_app:{password}@{host}/utility_lab"
    raise ValueError(f"unknown MongoDB target {target!r}: use 'local' or 'atlas'")