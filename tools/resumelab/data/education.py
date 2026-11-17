"""Degrees, boards and (fictional) institutions.

Institution names are invented; they are built to sound like ordinary Indian and international
colleges without naming a real one or a real person.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Degree:
    key: str
    level: str  # SCHOOL | DIPLOMA | BACHELOR | MASTER | DOCTORATE | PROFESSIONAL
    short: tuple[str, ...]  # rendered variants, e.g. "B.Tech", "Bachelor of Technology"
    field: str  # rendered field, e.g. "Computer Science and Engineering"
    family: str  # field family used by job requirements: computer science, commerce, ...
    years: int  # programme length
    kind: str = "university"  # university | college | professional


def _d(key, level, short, field, family, years, kind="university"):
    return Degree(key, level, tuple(short), field, family, years, kind)


DEGREES: dict[str, Degree] = {d.key: d for d in (
    _d("btech_cse", "BACHELOR", ("B.Tech", "Bachelor of Technology", "B.Tech."), "Computer Science and Engineering", "computer science", 4),
    _d("be_it", "BACHELOR", ("B.E.", "Bachelor of Engineering", "BE"), "Information Technology", "computer science", 4),
    _d("btech_ece", "BACHELOR", ("B.Tech", "Bachelor of Technology"), "Electronics and Communication Engineering", "electronics", 4),
    _d("btech_ee", "BACHELOR", ("B.Tech", "B.E."), "Electrical and Electronics Engineering", "electrical", 4),
    _d("bca", "BACHELOR", ("BCA", "Bachelor of Computer Applications"), "Computer Applications", "computer science", 3),
    _d("bsc_cs", "BACHELOR", ("B.Sc", "B.Sc.", "Bachelor of Science"), "Computer Science", "computer science", 3),
    _d("bsc_stats", "BACHELOR", ("B.Sc", "B.Sc. (Hons)"), "Statistics", "statistics", 3),
    _d("bsc_maths", "BACHELOR", ("B.Sc", "B.Sc. (Hons)"), "Mathematics", "mathematics", 3),
    _d("bsc_physics", "BACHELOR", ("B.Sc",), "Physics", "physics", 3),
    _d("btech_civil", "BACHELOR", ("B.Tech", "B.E.", "Bachelor of Engineering"), "Civil Engineering", "civil engineering", 4),
    _d("btech_mech", "BACHELOR", ("B.Tech", "B.E.", "Bachelor of Engineering"), "Mechanical Engineering", "mechanical engineering", 4),
    _d("bdes", "BACHELOR", ("B.Des", "Bachelor of Design"), "Communication Design", "design", 4),
    _d("bfa", "BACHELOR", ("BFA", "Bachelor of Fine Arts"), "Applied Arts", "design", 4),
    _d("bcom", "BACHELOR", ("B.Com", "B.Com.", "Bachelor of Commerce"), "Commerce", "commerce", 3),
    _d("bcom_hons", "BACHELOR", ("B.Com (Hons)", "B.Com. (Honours)"), "Accounting and Finance", "commerce", 3),
    _d("bba", "BACHELOR", ("BBA", "Bachelor of Business Administration"), "Business Administration", "business administration", 3),
    _d("bms", "BACHELOR", ("BMS",), "Management Studies", "business administration", 3),
    _d("ba_eng", "BACHELOR", ("B.A.", "BA", "Bachelor of Arts"), "English", "english", 3),
    _d("ba_psych", "BACHELOR", ("B.A.", "BA (Hons)"), "Psychology", "psychology", 3),
    _d("ba_eco", "BACHELOR", ("B.A.", "BA (Hons)"), "Economics", "economics", 3),
    _d("bsc_nursing", "BACHELOR", ("B.Sc Nursing", "B.Sc. Nursing", "Bachelor of Science in Nursing"), "Nursing", "nursing", 4),
    _d("gnm", "DIPLOMA", ("GNM", "Diploma in General Nursing and Midwifery"), "General Nursing and Midwifery", "nursing", 3),
    _d("bed", "BACHELOR", ("B.Ed", "B.Ed.", "Bachelor of Education"), "Education", "education", 2),
    _d("dled", "DIPLOMA", ("D.El.Ed", "Diploma in Elementary Education"), "Elementary Education", "education", 2),
    _d("llb", "BACHELOR", ("LL.B.", "LLB", "B.A. LL.B. (Hons)"), "Law", "law", 5),
    _d("bhm", "BACHELOR", ("BHM",), "Hotel Management", "hospitality", 4),
    _d("diploma_civil", "DIPLOMA", ("Diploma", "Polytechnic Diploma"), "Civil Engineering", "civil engineering", 3),
    _d("diploma_mech", "DIPLOMA", ("Diploma", "Polytechnic Diploma"), "Mechanical Engineering", "mechanical engineering", 3),
    # Postgraduate
    _d("mtech_cse", "MASTER", ("M.Tech", "Master of Technology"), "Computer Science", "computer science", 2),
    _d("mtech_struct", "MASTER", ("M.Tech", "M.E."), "Structural Engineering", "civil engineering", 2),
    _d("mtech_mech", "MASTER", ("M.Tech", "M.E."), "Thermal Engineering", "mechanical engineering", 2),
    _d("mca", "MASTER", ("MCA", "Master of Computer Applications"), "Computer Applications", "computer science", 2),
    _d("msc_ds", "MASTER", ("M.Sc", "M.Sc."), "Data Science", "data science", 2),
    _d("msc_stats", "MASTER", ("M.Sc", "M.Stat"), "Statistics", "statistics", 2),
    _d("mba", "MASTER", ("MBA", "PGDM", "Master of Business Administration"), "Marketing", "business administration", 2),
    _d("mba_hr", "MASTER", ("MBA", "PGDM"), "Human Resource Management", "business administration", 2),
    _d("mba_fin", "MASTER", ("MBA", "PGDM"), "Finance", "business administration", 2),
    _d("mba_ops", "MASTER", ("MBA", "PGDM"), "Operations and Supply Chain", "business administration", 2),
    _d("mcom", "MASTER", ("M.Com", "M.Com."), "Commerce", "commerce", 2),
    _d("ma_eng", "MASTER", ("M.A.", "MA"), "English Literature", "english", 2),
    _d("msc_nursing", "MASTER", ("M.Sc Nursing", "M.Sc. Nursing"), "Medical-Surgical Nursing", "nursing", 2),
    _d("llm", "MASTER", ("LL.M.", "LLM"), "Corporate and Commercial Law", "law", 1),
    _d("mdes", "MASTER", ("M.Des",), "Interaction Design", "design", 2),
    _d("ms_cs", "MASTER", ("M.S.", "MS", "Master of Science"), "Computer Science", "computer science", 2),
    _d("phd_cs", "DOCTORATE", ("Ph.D.", "PhD"), "Computer Science", "computer science", 5),
    _d("phd_edu", "DOCTORATE", ("Ph.D.", "PhD"), "Education", "education", 5),
    # Professional qualifications
    _d("ca", "PROFESSIONAL", ("CA", "Chartered Accountant"), "Chartered Accountancy (ICAI)", "commerce", 4, "professional"),
    _d("cs", "PROFESSIONAL", ("CS", "Company Secretary"), "Company Secretary (ICSI)", "law", 3, "professional"),
    _d("cma", "PROFESSIONAL", ("CMA", "Cost and Management Accountant"), "Cost and Management Accountancy", "commerce", 3, "professional"),
)}

LEVEL_RANK = {"SCHOOL": 0, "DIPLOMA": 1, "BACHELOR": 2, "PROFESSIONAL": 3, "MASTER": 3, "DOCTORATE": 4}

BOARDS_10 = ("CBSE", "ICSE", "BSE Odisha", "Maharashtra State Board", "Tamil Nadu State Board", "West Bengal Board (WBBSE)",
             "Karnataka State Board (KSEEB)", "Kerala State Board", "UP Board", "Bihar School Examination Board", "Gujarat State Board (GSEB)",
             "Andhra Pradesh State Board", "Punjab School Education Board")
BOARDS_12 = ("CBSE", "ISC", "CHSE Odisha", "Maharashtra State Board (HSC)", "Tamil Nadu State Board (HSC)", "WBCHSE",
             "Karnataka PU Board", "Kerala Higher Secondary Board", "UP Board", "BSEB (Intermediate)", "GSHSEB", "AP Intermediate Board",
             "Punjab School Education Board")
STREAMS_12 = {"science": "Science (PCM)", "bio": "Science (PCB)", "commerce": "Commerce", "arts": "Humanities"}

_PLACE_WORDS = ("Kaveri", "Shivalik", "Narmada", "Amaltas", "Deccan", "Godavari", "Mahanadi", "Konark", "Sahyadri Ridge", "Nilgiri View",
                "Brahmaputra Valley", "Aravalli", "Vindhya", "Tapti", "Coromandel", "Malabar Coast", "Chilika", "Kalinga Crest", "Satpura",
                "Western Ghats", "Ganga Plains", "Banyan Grove", "Lotus Valley", "Palash", "Tamarind Hill", "Sandalwood")
_INSTITUTE_PATTERNS = {
    "engineering": ("{p} Institute of Technology", "{p} College of Engineering", "{p} Institute of Engineering and Technology",
                    "{p} University of Science and Technology", "{p} Engineering College"),
    "general": ("{p} University", "{p} College of Arts and Science", "{p} Degree College", "{p} Autonomous College"),
    "commerce": ("{p} College of Commerce", "{p} School of Commerce and Economics", "{p} College"),
    "management": ("{p} School of Management", "{p} Institute of Management Studies", "{p} Business School"),
    "nursing": ("{p} College of Nursing", "{p} Institute of Nursing Sciences", "{p} School of Nursing"),
    "education": ("{p} College of Teacher Education", "{p} Institute of Education", "{p} B.Ed College"),
    "law": ("{p} Law College", "{p} School of Law", "{p} National Law Institute"),
    "design": ("{p} Institute of Design", "{p} School of Art and Design", "{p} College of Fine Arts"),
    "polytechnic": ("{p} Polytechnic", "Government Polytechnic, {p}"),
    "intl": ("{p} State University", "University of {p}", "{p} Polytechnic Institute", "{p} College"),
}
INTL_PLACES = ("Eastbrook", "Harbour Point", "Westmere", "Northgate", "Riverside Heights", "Kingsford", "Ashton Vale", "Lakemont",
               "Port Hollis", "Greyfield", "Brightwater", "Maple Ridge")
SCHOOL_NAMES = ("Green Valley Public School", "St. Xavier's High School", "Saraswati Vidya Mandir", "Little Flower Convent School",
                "Delhi Public Academy", "Sunrise English Medium School", "Holy Cross Higher Secondary School", "Modern Public School",
                "Gyan Jyoti Senior Secondary School", "Vidya Bharati High School", "Carmel Convent School", "Sri Chaitanya Junior College",
                "Narayana Junior College", "Government Higher Secondary School", "Adarsh Vidyalaya", "Blue Bells Senior Secondary School")

DEGREE_INSTITUTE_KIND = {
    "computer science": "engineering", "electronics": "engineering", "electrical": "engineering", "civil engineering": "engineering",
    "mechanical engineering": "engineering", "statistics": "general", "mathematics": "general", "physics": "general",
    "design": "design", "commerce": "commerce", "business administration": "management", "english": "general",
    "psychology": "general", "economics": "general", "nursing": "nursing", "education": "education", "law": "law",
    "hospitality": "general", "data science": "general",
}


def institution(rng, degree: Degree, indian: bool) -> str:
    if degree.kind == "professional":
        return {"ca": "The Institute of Chartered Accountants of India (ICAI)", "cs": "The Institute of Company Secretaries of India (ICSI)",
                "cma": "The Institute of Cost Accountants of India"}.get(degree.key, "Professional body")
    if not indian:
        return rng.choice(_INSTITUTE_PATTERNS["intl"]).format(p=rng.choice(INTL_PLACES))
    kind = "polytechnic" if degree.level == "DIPLOMA" and degree.family in {"civil engineering", "mechanical engineering"} else \
        DEGREE_INSTITUTE_KIND.get(degree.family, "general")
    return rng.choice(_INSTITUTE_PATTERNS[kind]).format(p=rng.choice(_PLACE_WORDS))


def affiliating_university(rng) -> str:
    """The affiliating university / board column of Indian education tables, e.g. "Mahanadi Technical University"."""
    return f"{rng.choice(_PLACE_WORDS)} {rng.choice(('Technical University', 'University', 'State University'))}"
