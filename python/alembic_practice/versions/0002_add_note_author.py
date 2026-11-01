"""Add the column author to practice_note.

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-30
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("practice_note", sa.Column("author", sa.String(), nullable=True))


def downgrade() -> None:
    op.drop_column("practice_note", "author")