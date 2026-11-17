"""Persona generation: name, gender, region, contact details, personal details."""
from __future__ import annotations

import random
import re
from datetime import date

from resumelab.data.personas import BLOCKED_FULL_NAMES, EMAIL_DOMAINS, FATHER_FIRST, INTERESTS, REGIONS, UNISEX
from resumelab.model import Persona

_INTL_PHONES = {"US": lambda r: f"+1 ({r.randint(201, 989)}) 555-01{r.randint(0, 99):02d}",
                "SG": lambda r: f"+65 {r.choice('689')}{r.randint(100, 999)} {r.randint(1000, 9999)}",
                "NG": lambda r: f"+234 80{r.randint(0, 9)} {r.randint(100, 999)} {r.randint(1000, 9999)}",
                "AE": lambda r: f"+971 5{r.choice('0245')} {r.randint(100, 999)} {r.randint(1000, 9999)}",
                "DE": lambda r: f"+49 15{r.randint(1, 9)} {r.randint(1000000, 9999999)}",
                "ES": lambda r: f"+34 6{r.randint(10, 99)} {r.randint(100, 999)} {r.randint(100, 999)}",
                "FR": lambda r: f"+33 6 {r.randint(10, 99)} {r.randint(10, 99)} {r.randint(10, 99)} {r.randint(10, 99)}",
                "JP": lambda r: f"+81 90-{r.randint(1000, 9999)}-{r.randint(1000, 9999)}",
                "KR": lambda r: f"+82 10-{r.randint(1000, 9999)}-{r.randint(1000, 9999)}",
                "KE": lambda r: f"+254 7{r.randint(10, 99)} {r.randint(100, 999)} {r.randint(100, 999)}",
                "GH": lambda r: f"+233 24 {r.randint(100, 999)} {r.randint(1000, 9999)}",
                "BD": lambda r: f"+880 17{r.randint(10, 99)} {r.randint(100000, 999999)}",
                "NP": lambda r: f"+977 98{r.randint(10000000, 99999999)}",
                "LK": lambda r: f"+94 7{r.randint(0, 8)} {r.randint(100, 999)} {r.randint(1000, 9999)}"}


_NATIONALITY = {"USA": "American", "United Kingdom": "British", "Singapore": "Singaporean", "Malaysia": "Malaysian", "Japan": "Japanese",
                "South Korea": "South Korean", "Taiwan": "Taiwanese", "Nigeria": "Nigerian", "Kenya": "Kenyan", "Ghana": "Ghanaian",
                "South Africa": "South African", "Rwanda": "Rwandan", "United Arab Emirates": "Emirati", "Qatar": "Qatari", "Oman": "Omani",
                "Jordan": "Jordanian", "Germany": "German", "Spain": "Spanish", "Nepal": "Nepali", "Portugal": "Portuguese", "France": "French", "Brazil": "Brazilian",
                "Mexico": "Mexican", "Italy": "Italian", "Sri Lanka": "Sri Lankan", "Bangladesh": "Bangladeshi", "Nepal": "Nepali"}


def _indian_phone(rng: random.Random) -> str:
    digits = f"{rng.choice('6789')}{rng.randint(0, 999999999):09d}"
    style = rng.choice(("plain", "spaced", "dash", "plain91", "brackets"))
    return {"plain": f"+91 {digits}", "spaced": f"+91 {digits[:5]} {digits[5:]}", "dash": f"+91-{digits}",
            "plain91": f"91{digits}" if rng.random() < 0.3 else digits, "brackets": f"(+91) {digits[:5]}-{digits[5:]}"}[style]


def _handle(first: str, last: str, rng: random.Random) -> str:
    first_clean = re.sub(r"[^a-z]", "", first.lower())
    last_clean = re.sub(r"[^a-z]", "", last.lower())
    style = rng.choice(("dot", "plain", "num", "initial", "under"))
    return {"dot": f"{first_clean}.{last_clean}", "plain": f"{first_clean}{last_clean}", "num": f"{first_clean}{last_clean}{rng.randint(1, 99)}",
            "initial": f"{first_clean[0]}{last_clean}", "under": f"{first_clean}_{last_clean}{rng.randint(90, 99)}"}[style]


def make_persona(rng: random.Random, profession_family: str, as_of_year: int, age_hint: int, avoid: set[str] | None = None) -> Persona:
    region = rng.choices(REGIONS, weights=[region.weight for region in REGIONS])[0]
    roll = rng.random()
    gender = "F" if roll < 0.485 else ("M" if roll < 0.97 else "X")
    for _ in range(20):
        if gender == "X":
            first = rng.choice(UNISEX["IN" if region.indian else "INTL"])
        else:
            first = rng.choice(region.female if gender == "F" else region.male)
        last = rng.choice(region.surnames)
        if region.key == "punjab" and last in {"Kaur", "Singh"}:
            last = "Kaur" if gender == "F" else ("Singh" if gender == "M" else rng.choice(("Gill", "Sandhu")))
        if f"{first} {last}" not in BLOCKED_FULL_NAMES and first != last and f"{first} {last}" not in (avoid or set()):
            break
    handle = _handle(first, last, rng)
    email = f"{handle}@{rng.choice(EMAIL_DOMAINS)}"
    phone = _indian_phone(rng) if region.indian else _INTL_PHONES[region.phone](rng)
    tag = f"{rng.randrange(16 ** 4):04x}"
    linkedin = f"linkedin.com/in/{handle.replace('.', '-').replace('_', '-')}-{tag}" if rng.random() < 0.75 else None
    github = f"github.com/{handle.replace('.', '')}" if profession_family == "tech" and rng.random() < 0.7 else None
    portfolio = None
    if profession_family == "design":
        portfolio = rng.choice((f"behance.net/{handle.replace('.', '')}", f"{handle.replace('.', '')}.example.design", f"dribbble.com/{handle.replace('.', '')}"))
    elif profession_family == "tech" and rng.random() < 0.2:
        portfolio = f"{handle.replace('.', '')}.example.dev"
    birth_year = as_of_year - age_hint
    dob = date(birth_year, rng.randint(1, 12), rng.randint(1, 28))
    married_probability = min(0.85, max(0.0, (age_hint - 24) * 0.09))
    marital = "Married" if rng.random() < married_probability else "Single"
    languages = ["English"] + list(rng.sample(region.languages, min(len(region.languages), rng.randint(1, 2))))
    if region.indian and "Hindi" not in languages and rng.random() < 0.6:
        languages.append("Hindi")
    father = None
    if region.indian:
        father_first = rng.choice(FATHER_FIRST["IN"])
        father = f"{rng.choice(('Mr. ', 'Shri ', ''))}{father_first} {last if last not in {'Kaur'} else 'Singh'}"
    city = rng.choice(region.cities)
    country = "India" if region.indian else city.split(", ")[-1]
    nationality = "Indian" if region.indian else _NATIONALITY.get(country, country)
    return Persona(first=first, last=last, gender=gender, region=region.key, indian=region.indian, city=city,
                   country=country, email=email, phone=phone, linkedin=linkedin, github=github, portfolio=portfolio, dob=dob,
                   marital=marital, nationality=nationality, father=father, languages=languages,
                   interests=rng.sample(INTERESTS, rng.randint(2, 4)))
