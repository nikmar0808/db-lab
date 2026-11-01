"""The six tables as SQLAlchemy ORM classes, in the typed style of SQLAlchemy 2 (Stage 3, lesson A6).

The counterpart of the Java project's JPA entities. Each class maps one table; each attribute annotated with
Mapped[...] maps one column, and the annotation gives its Python type. Because the types are written out,
type checkers such as mypy can follow the code: the style eai-backend-poc moves to in Stage 8.

These classes describe tables that Flyway built (Stage 2). SQLAlchemy never creates or changes them here.
"""

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import DateTime, ForeignKey, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    """The common parent of every mapped class; it holds the list of tables they describe (Base.metadata)."""


class Customer(Base):
    __tablename__ = "customer"

    id: Mapped[int] = mapped_column(primary_key=True)  # numbered by the database's identity column
    name: Mapped[str]
    email: Mapped[str]
    # timestamptz: DateTime(timezone=True). A bare Mapped[datetime] would mean timestamp WITHOUT time zone.
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    # The other side of Premise.customer: the customer's premises, loaded when first used (lazy).
    premises: Mapped[list["Premise"]] = relationship(back_populates="customer")


class Tariff(Base):
    __tablename__ = "tariff"

    code: Mapped[str] = mapped_column(primary_key=True)  # a natural key: no identity column
    description: Mapped[str]
    rate_per_kwh: Mapped[Decimal]
    standing_charge: Mapped[Decimal]


class Premise(Base):
    __tablename__ = "premise"

    id: Mapped[int] = mapped_column(primary_key=True)
    customer_id: Mapped[int] = mapped_column(ForeignKey("customer.id"))
    address: Mapped[str]
    tariff_code: Mapped[str] = mapped_column(ForeignKey("tariff.code"))

    customer: Mapped[Customer] = relationship(back_populates="premises")
    tariff: Mapped[Tariff] = relationship()


class Meter(Base):
    __tablename__ = "meter"

    id: Mapped[int] = mapped_column(primary_key=True)
    premise_id: Mapped[int] = mapped_column(ForeignKey("premise.id"))
    serial_number: Mapped[str]
    installed_on: Mapped[date]


class MeterReading(Base):
    __tablename__ = "meter_reading"

    id: Mapped[int] = mapped_column(primary_key=True)
    meter_id: Mapped[int] = mapped_column(ForeignKey("meter.id"))
    read_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))  # timestamptz
    kwh: Mapped[Decimal]


class Bill(Base):
    __tablename__ = "bill"

    id: Mapped[int] = mapped_column(primary_key=True)
    customer_id: Mapped[int] = mapped_column(ForeignKey("customer.id"))
    premise_id: Mapped[int] = mapped_column(ForeignKey("premise.id"))
    period_start: Mapped[date]
    period_end: Mapped[date]
    amount: Mapped[Decimal]
    status: Mapped[str] = mapped_column(default="DRAFT")