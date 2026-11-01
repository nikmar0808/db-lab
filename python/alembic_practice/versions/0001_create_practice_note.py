"""Create the table practice_note.

Revision ID: 0001
Revises:
Create Date: 2026-10-30
"""

import sqlalchemy as sa
from alembic import op

# The chain: this migration's id, and the one before it (None: this is the first).
revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Move the database forward: create the table."""
    op.create_table(
        "practice_note",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("body", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    """Move the database back: undo exactly what upgrade() did."""
    op.drop_table("practice_note")