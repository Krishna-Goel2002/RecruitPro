"""FastAPI dependencies: settings, the network factory (real / demo / test) and the clock."""
from __future__ import annotations

from datetime import datetime
from typing import Callable

from app.core.clock import utc_now
from app.core.config import Settings, load_settings
from app.network import Network, is_demo_identifier

NetworkFactory = Callable[[Settings, str, "str | None"], Network]


def build_network(settings: Settings, identifier: str, code: str | None = None) -> Network:
    """Demo fixtures for ``demo-`` identifiers when enabled, else the real network."""
    if settings.demo_fixtures and is_demo_identifier(identifier):
        from app.demo.fixtures import demo_network

        return demo_network(code)
    return Network()


def get_settings() -> Settings:
    return load_settings()


def get_network_factory() -> NetworkFactory:
    return build_network


def get_clock() -> Callable[[], datetime]:
    return utc_now
