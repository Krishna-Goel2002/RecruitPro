"""Writing-style vocabulary: section headings, date formats, objectives, declarations, typos and Indian English."""
from __future__ import annotations

HEADINGS: dict[str, tuple[str, ...]] = {
    "SUMMARY": ("Summary", "Professional Summary", "Profile", "Profile Summary", "About Me", "Career Summary", "Professional Profile"),
    "OBJECTIVE": ("Career Objective", "Objective", "Career Goal"),
    "SKILLS": ("Skills", "Technical Skills", "Key Skills", "Core Competencies", "Skills & Tools", "Areas of Expertise", "Technical Proficiency",
               "Skill Set"),
    "EXPERIENCE": ("Experience", "Work Experience", "Professional Experience", "Employment History", "Career History", "Work History",
                   "Organisational Experience", "Professional Journey", "Experience Summary"),
    "EDUCATION": ("Education", "Academic Qualifications", "Educational Qualifications", "Academic Background", "Qualifications",
                  "Education & Training"),
    "CERTIFICATIONS": ("Certifications", "Licenses & Certifications", "Certifications & Trainings", "Professional Certifications", "Courses & Certifications"),
    "PROJECTS": ("Projects", "Academic Projects", "Key Projects", "Personal Projects", "Project Details"),
    "ACHIEVEMENTS": ("Achievements", "Awards & Achievements", "Key Achievements", "Honours and Awards", "Accomplishments"),
    "LANGUAGES": ("Languages", "Languages Known", "Language Proficiency"),
    "INTERESTS": ("Hobbies", "Interests", "Hobbies & Interests", "Extra-curricular Activities"),
    "PERSONAL": ("Personal Details", "Personal Information", "Personal Profile", "Personal Dossier"),
    "DECLARATION": ("Declaration",),
    "REFERENCES": ("References", "Referees"),
    "PUBLICATIONS": ("Publications", "Selected Publications", "Research Publications"),
    "INTERNSHIPS": ("Internships", "Internship & Training", "Industrial Training"),
    "STRENGTHS": ("Strengths", "Key Strengths", "Personal Strengths"),
    "TEACHING": ("Teaching", "Teaching Experience"),
    "CONFERENCES": ("Conferences & Presentations", "Conference Presentations"),
}

MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
FULL_MONTHS = ("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
# style -> weight. "year" (year-only) is rare because it hides the month from every reader.
DATE_STYLES = {"mon": 30, "full": 14, "num": 12, "iso": 6, "apos": 10, "mon_dash": 6, "sept": 4, "year": 4}
PRESENT = ("Present", "Present", "Current", "Till date", "till date", "Now", "Ongoing", "Date")
SEPARATORS = (" – ", " - ", " to ", " — ", " – ", " - ")

OBJECTIVES = (
    "To work in a challenging environment where I can utilise my skills and knowledge for the growth of the organisation as well as my own.",
    "Seeking a position in a reputed organisation where I can apply my knowledge and grow professionally.",
    "To obtain a responsible position in a progressive company that offers opportunities to learn and contribute.",
    "Looking for a challenging role in a reputed organisation to utilise my skills for the betterment of the company.",
    "To secure a position where I can contribute to team success while continuously learning new things.",
    "Aspiring {label} seeking an entry-level role to apply what I learned during my degree and internships.",
)
DECLARATIONS = (
    "I hereby declare that the information furnished above is true to the best of my knowledge and belief.",
    "I hereby declare that all the details mentioned above are correct and true to the best of my knowledge.",
    "I do hereby declare that the above information is true and correct to the best of my knowledge and belief.",
)
STRENGTHS = ("Quick learner", "Positive attitude", "Hard working and sincere", "Good communication skills", "Ability to work in a team",
             "Self-motivated", "Adaptable to new environments", "Punctual and disciplined")
ACHIEVEMENTS = (
    "Received the 'Star Performer' award in {year}", "Ranked in the top 5% of the batch in final-year examinations",
    "Secured 2nd prize in an inter-college hackathon ({year})", "Employee of the Quarter, Q{q} {year}",
    "Completed a 21 km half marathon in {year}", "Volunteered as a mentor for a local NGO's after-school programme",
    "Merit scholarship holder for all four years of graduation", "Won the best paper award at a national student conference ({year})",
    "Organised the annual college tech fest with {n} participants", "NSS volunteer - organised blood donation camps",
)
REFERENCES = ("Available on request.", "References available upon request.")

# Indian-English and weak-writing substitutions for template text (never applied inside skill names).
INDIAN_ENGLISH = (
    ("Led ", "Handled "), ("Completed ", "Done "), ("Managed ", "Handled "), ("Worked with", "Worked along with"),
    ("Responsible for", "Was responsible for"), ("Built ", "Did the development of "), ("Prepared ", "Did the preparation of "),
    ("Coordinated ", "Did coordination "),
)
INDIAN_PREFIXES = ("Having good knowledge of ", "Good exposure in ", "Involved in ", "Actively involved in ", "Worked as a part of team for ")
TYPOS = {
    "responsible": "responsibile", "management": "managment", "experience": "experiance", "achieved": "acheived",
    "successfully": "sucessfully", "environment": "enviroment", "maintenance": "maintainance", "received": "recieved",
    "knowledge": "knowlege", "development": "developement", "separate": "seperate", "occurred": "occured", "efficient": "effecient",
    "independently": "independantly", "committee": "commitee", "professional": "proffesional", "required": "requried",
    "calendar": "calender", "accommodate": "accomodate", "business": "buisness", "performance": "perfomance", "process": "proccess",
    "reports": "reprots", "customers": "cusotmers", "students": "studnets", "patients": "pateints", "quality": "qualtiy",
}
WEAK_VERBS = ("Worked on", "Involved in", "Helped in", "Assisted in", "Participated in", "Handled", "Responsible for")

NON_ENGLISH = {
    "de": ("Kurzprofil", "Engagierte Fachkraft mit mehrjähriger Berufserfahrung. Teamfähig, zuverlässig und lernbereit."),
    "fr": ("Profil", "Professionnelle motivée avec plusieurs années d'expérience. Rigoureuse, autonome et orientée résultats."),
    "es": ("Perfil", "Profesional con varios años de experiencia, responsable, organizada y con muchas ganas de aprender."),
    "hi": ("परिचय", "मैं एक मेहनती और ईमानदार पेशेवर हूँ। मुझे टीम में काम करना और नई चीज़ें सीखना पसंद है।"),
    "pt": ("Perfil", "Profissional dedicada com experiência em equipes multidisciplinares e foco em resultados."),
}

BREAK_REASONS = (
    "Career break - caring for a family member", "Career break - relocation to a new city", "Career break - health and recovery",
    "Career break - preparing for competitive examinations", "Sabbatical - travel and volunteering", "Career break - parental leave",
    "Career break - family business", "Career break - personal reasons",
)
