"""Names, places and contact conventions by region.

Names are common given names and surnames combined at random; famous full-name combinations are
blocked. Regions are balanced across India (north, east, west, south, north-east; Hindu, Muslim,
Christian, Sikh, Jain, Parsi and Buddhist naming traditions) and abroad. No religion, caste or
community is ever written into a resume - names only.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Region:
    key: str
    country: str
    weight: float
    female: tuple[str, ...]
    male: tuple[str, ...]
    surnames: tuple[str, ...]
    cities: tuple[str, ...]  # "City, State" or "City, Country"
    languages: tuple[str, ...]  # besides English
    phone: str  # format key, see personas.phone()
    indian: bool = True


UNISEX = {
    "IN": ("Kiran", "Jyoti", "Shashi", "Santosh", "Ashwini"),
    "INTL": ("Alex", "Sam", "Jordan", "Taylor", "Robin", "Ariel"),
}

REGIONS: tuple[Region, ...] = (
    Region("north", "India", 0.13,
           ("Priya", "Neha", "Pooja", "Anjali", "Ritika", "Shreya", "Kavya", "Nidhi", "Swati", "Megha", "Aditi", "Tanvi", "Sakshi", "Divya"),
           ("Rahul", "Amit", "Rohit", "Ankit", "Saurabh", "Vikas", "Abhishek", "Nikhil", "Gaurav", "Varun", "Harsh", "Kunal", "Manish", "Deepak"),
           ("Sharma", "Verma", "Gupta", "Srivastava", "Tiwari", "Mishra", "Agarwal", "Saxena", "Pandey", "Chauhan", "Yadav", "Rastogi", "Bhatt", "Joshi"),
           ("Lucknow, Uttar Pradesh", "Noida, Uttar Pradesh", "New Delhi, Delhi", "Gurugram, Haryana", "Jaipur, Rajasthan", "Kanpur, Uttar Pradesh",
            "Dehradun, Uttarakhand", "Bhopal, Madhya Pradesh", "Indore, Madhya Pradesh"),
           ("Hindi",), "IN"),
    Region("punjab", "India", 0.05,
           ("Harpreet", "Simran", "Gurleen", "Jasleen", "Navneet", "Manpreet", "Amrit", "Rajveer"),
           ("Gurpreet", "Jaskaran", "Harjot", "Manjot", "Arshdeep", "Inderjit", "Kanwar", "Tejinder"),
           ("Kaur", "Singh", "Gill", "Sandhu", "Dhillon", "Grewal", "Sidhu", "Bajwa", "Ahluwalia", "Bedi"),
           ("Ludhiana, Punjab", "Amritsar, Punjab", "Mohali, Punjab", "Chandigarh, Chandigarh", "Jalandhar, Punjab"),
           ("Punjabi", "Hindi"), "IN"),
    Region("east", "India", 0.12,
           ("Ananya", "Sneha", "Riya", "Debolina", "Sayani", "Ipsita", "Smruti", "Subhashree", "Madhusmita", "Puja", "Moumita", "Barsha"),
           ("Sourav", "Arijit", "Subhankar", "Debasish", "Soumya", "Biswajit", "Satyajit", "Pritam", "Ashutosh", "Chinmay", "Sambit", "Tapas"),
           ("Das", "Mohanty", "Patnaik", "Sahoo", "Mishra", "Banerjee", "Chatterjee", "Mukherjee", "Ghosh", "Bose", "Pradhan", "Nayak", "Rout", "Jena"),
           ("Bhubaneswar, Odisha", "Cuttack, Odisha", "Kolkata, West Bengal", "Durgapur, West Bengal", "Patna, Bihar", "Ranchi, Jharkhand",
            "Rourkela, Odisha", "Sambalpur, Odisha"),
           ("Odia", "Bengali", "Hindi"), "IN"),
    Region("west", "India", 0.12,
           ("Sneha", "Mrunal", "Rutuja", "Pranali", "Hetal", "Krupa", "Dhwani", "Ishita", "Gauri", "Shruti", "Mitali", "Janhavi"),
           ("Omkar", "Aniket", "Tejas", "Sagar", "Parth", "Hardik", "Chirag", "Nishant", "Siddharth", "Akshay", "Yash", "Pratik"),
           ("Kulkarni", "Deshpande", "Patil", "Joshi", "Pawar", "Shinde", "Patel", "Shah", "Mehta", "Desai", "Parikh", "Gokhale", "Jadhav", "Trivedi"),
           ("Pune, Maharashtra", "Mumbai, Maharashtra", "Nagpur, Maharashtra", "Nashik, Maharashtra", "Ahmedabad, Gujarat", "Vadodara, Gujarat",
            "Surat, Gujarat", "Thane, Maharashtra", "Navi Mumbai, Maharashtra"),
           ("Marathi", "Gujarati", "Hindi"), "IN"),
    Region("south", "India", 0.20,
           ("Lakshmi", "Divya", "Keerthana", "Harini", "Sowmya", "Deepika", "Aishwarya", "Bhavana", "Sravani", "Anusha", "Meghana", "Nandini",
            "Reshma", "Athira", "Gayathri", "Vidya"),
           ("Karthik", "Arun", "Vignesh", "Praveen", "Srinivas", "Ravi", "Naveen", "Sai Kiran", "Harish", "Pradeep", "Rajesh", "Venkatesh",
            "Arjun", "Vishnu", "Akhil", "Suresh"),
           ("Iyer", "Krishnan", "Subramanian", "Reddy", "Rao", "Naidu", "Gowda", "Shetty", "Nair", "Menon", "Pillai", "Kumar", "Raghavan",
            "Murthy", "Hegde", "Prasad", "Varma", "Chowdary"),
           ("Bengaluru, Karnataka", "Chennai, Tamil Nadu", "Hyderabad, Telangana", "Coimbatore, Tamil Nadu", "Kochi, Kerala", "Mysuru, Karnataka",
            "Visakhapatnam, Andhra Pradesh", "Thiruvananthapuram, Kerala", "Madurai, Tamil Nadu", "Mangaluru, Karnataka", "Vijayawada, Andhra Pradesh"),
           ("Tamil", "Telugu", "Kannada", "Malayalam"), "IN"),
    Region("muslim", "India", 0.08,
           ("Ayesha", "Fatima", "Sana", "Zoya", "Mehnaz", "Nazia", "Rukhsar", "Shabnam", "Afreen", "Heena"),
           ("Mohammed", "Imran", "Faisal", "Arif", "Sameer", "Aamir", "Tanveer", "Irfan", "Shahid", "Zaid"),
           ("Khan", "Ahmed", "Qureshi", "Ansari", "Siddiqui", "Shaikh", "Syed", "Hussain", "Rizvi", "Mirza"),
           ("Hyderabad, Telangana", "Lucknow, Uttar Pradesh", "Aligarh, Uttar Pradesh", "Mumbai, Maharashtra", "Bengaluru, Karnataka",
            "Kozhikode, Kerala", "Srinagar, Jammu and Kashmir", "Bhopal, Madhya Pradesh"),
           ("Urdu", "Hindi"), "IN"),
    Region("christian", "India", 0.06,
           ("Anita", "Merlin", "Sherin", "Jincy", "Neethu", "Christina", "Rachel", "Sonia", "Leena", "Rosemary"),
           ("Joseph", "Thomas", "Jibin", "Sebastian", "Anson", "Melvin", "Roshan", "Francis", "Jerin", "Allen"),
           ("D'Souza", "Fernandes", "Mathew", "Thomas", "George", "Varghese", "Pereira", "Rodrigues", "Kurian", "Abraham", "Dsilva", "Lobo"),
           ("Kochi, Kerala", "Kottayam, Kerala", "Panaji, Goa", "Margao, Goa", "Mangaluru, Karnataka", "Chennai, Tamil Nadu", "Mumbai, Maharashtra"),
           ("Malayalam", "Konkani"), "IN"),
    Region("jain_parsi", "India", 0.03,
           ("Khushi", "Mansi", "Riddhi", "Zenobia", "Shirin", "Delnaz", "Vidhi", "Palak"),
           ("Jinesh", "Rushabh", "Darius", "Cyrus", "Kayzad", "Sahil", "Vatsal", "Rohan"),
           ("Jain", "Shah", "Mehta", "Doshi", "Sanghvi", "Irani", "Bharucha", "Contractor", "Wadia", "Kothari"),
           ("Mumbai, Maharashtra", "Ahmedabad, Gujarat", "Surat, Gujarat", "Pune, Maharashtra", "Jaipur, Rajasthan"),
           ("Gujarati", "Hindi"), "IN"),
    Region("northeast", "India", 0.04,
           ("Lalrinpuii", "Bidisha", "Pallavi", "Tenzin", "Chingkhei", "Rinchen", "Mousumi", "Kangkana"),
           ("Lalremruata", "Dhruba", "Pranjal", "Tenzing", "Bijoy", "Nabajit", "Ronald", "Hriday"),
           ("Baruah", "Bora", "Gogoi", "Hazarika", "Saikia", "Sangma", "Bhutia", "Lepcha", "Singha", "Ralte"),
           ("Guwahati, Assam", "Shillong, Meghalaya", "Gangtok, Sikkim", "Imphal, Manipur", "Aizawl, Mizoram", "Dibrugarh, Assam"),
           ("Assamese", "Hindi"), "IN"),
    # International
    Region("us_uk", "United States", 0.04,
           ("Emily", "Sarah", "Jessica", "Hannah", "Olivia", "Grace", "Megan", "Laura"),
           ("Michael", "David", "Daniel", "James", "Ryan", "Matthew", "Thomas", "Andrew"),
           ("Miller", "Carter", "Bennett", "Brooks", "Turner", "Hughes", "Parker", "Collins", "Foster", "Reed"),
           ("Austin, TX, USA", "Columbus, OH, USA", "Raleigh, NC, USA", "Manchester, United Kingdom", "Leeds, United Kingdom", "Denver, CO, USA"),
           ("Spanish", "French"), "US", indian=False),
    Region("chinese", "Singapore", 0.01,
           ("Mei Ling", "Xin Yi", "Hui Min", "Jia Hui"), ("Wei Jie", "Jun Hao", "Zhi Hao", "Kai Wen"),
           ("Tan", "Lim", "Wong", "Ng", "Chen", "Lee", "Goh"),
           ("Singapore, Singapore", "Kuala Lumpur, Malaysia", "Taipei, Taiwan"), ("Mandarin",), "SG", indian=False),
    Region("japan", "Japan", 0.006, ("Yuki", "Haruka", "Aiko"), ("Kenji", "Takumi", "Haruto"), ("Sato", "Nakamura", "Tanaka", "Suzuki"),
           ("Osaka, Japan", "Tokyo, Japan"), ("Japanese",), "JP", indian=False),
    Region("korea", "South Korea", 0.006, ("Ji-woo", "Seo-yeon", "Min-ji"), ("Min-jun", "Hyun-woo", "Do-yoon"), ("Kim", "Park", "Choi", "Jung"),
           ("Seoul, South Korea", "Busan, South Korea"), ("Korean",), "KR", indian=False),
    Region("nigeria", "Nigeria", 0.01, ("Chiamaka", "Ngozi", "Adaeze", "Folake"), ("Chukwuemeka", "Oluwaseun", "Babatunde", "Emeka"),
           ("Okafor", "Adeyemi", "Okonkwo", "Balogun", "Eze"), ("Lagos, Nigeria", "Abuja, Nigeria"), ("French",), "NG", indian=False),
    Region("east_south_africa", "Kenya", 0.01, ("Wanjiru", "Akinyi", "Zanele", "Thandiwe"), ("Kipchoge", "Otieno", "Sipho", "Themba"),
           ("Mwangi", "Otieno", "Ndlovu", "Dlamini", "Kamau"), ("Nairobi, Kenya", "Johannesburg, South Africa", "Cape Town, South Africa"),
           ("French",), "KE", indian=False),
    Region("ghana", "Ghana", 0.005, ("Akosua", "Abena"), ("Kwame", "Kofi"), ("Mensah", "Boateng", "Owusu"), ("Accra, Ghana", "Kumasi, Ghana"),
           ("French",), "GH", indian=False),
    Region("mena", "United Arab Emirates", 0.02,
           ("Layla", "Noor", "Mariam", "Yasmin", "Hala", "Rania"),
           ("Omar", "Khalid", "Youssef", "Tariq", "Karim", "Hamza"),
           ("Haddad", "Mansour", "Nasser", "Saleh", "Khoury", "Farouk", "Rahman"),
           ("Dubai, United Arab Emirates", "Abu Dhabi, United Arab Emirates", "Doha, Qatar", "Muscat, Oman", "Amman, Jordan"),
           ("Arabic", "French"), "AE", indian=False),
    Region("iberian_latam", "Spain", 0.01, ("Sofia", "Lucia", "Ana", "Mariana"), ("Mateo", "Diego", "Rafael", "Tomas"),
           ("Garcia", "Fernandez", "Silva", "Costa", "Lopez", "Martinez"), ("Madrid, Spain", "Lisbon, Portugal", "Sao Paulo, Brazil", "Mexico City, Mexico"),
           ("Spanish", "Portuguese"), "ES", indian=False),
    Region("german", "Germany", 0.008, ("Lena", "Julia", "Hannah"), ("Felix", "Jonas", "Lukas"), ("Schmidt", "Weber", "Fischer", "Wagner"),
           ("Berlin, Germany", "Munich, Germany", "Hamburg, Germany"), ("German",), "DE", indian=False),
    Region("french_italian", "France", 0.007, ("Camille", "Elena", "Chiara"), ("Julien", "Matteo", "Louis"), ("Moreau", "Rossi", "Bernard", "Ricci"),
           ("Lyon, France", "Paris, France", "Milan, Italy"), ("French", "Italian"), "FR", indian=False),
    Region("sri_lanka", "Sri Lanka", 0.008, ("Nadeesha", "Dilini"), ("Kasun", "Tharindu"), ("Perera", "Fernando", "Jayasinghe", "Silva"),
           ("Colombo, Sri Lanka", "Kandy, Sri Lanka"), ("Tamil",), "LK", indian=False),
    Region("bangladesh", "Bangladesh", 0.007, ("Sabina", "Farzana", "Nusrat"), ("Rafiq", "Tanvir", "Arif"), ("Rahman", "Hossain", "Chowdhury", "Islam"),
           ("Dhaka, Bangladesh", "Chittagong, Bangladesh"), ("Bengali",), "BD", indian=False),
    Region("nepal", "Nepal", 0.005, ("Anjana", "Sushmita"), ("Bishal", "Anup"), ("Shrestha", "Thapa", "Gurung", "Rai"),
           ("Kathmandu, Nepal", "Pokhara, Nepal"), ("Hindi",), "NP", indian=False),
)

# Full names that belong to well-known people are never produced.
BLOCKED_FULL_NAMES = frozenset({
    "Virat Kohli", "Rahul Dravid", "Amit Shah", "Priya Sharma", "Rohit Sharma", "Rahul Gandhi", "Arijit Singh", "Sourav Ganguly",
    "Michael Jordan", "David Miller", "Mohammed Shami", "Imran Khan", "Aamir Khan", "Sania Mirza", "Daniel Craig",
})

# Fake contact domains (RFC 2606 style "example" names), with a realistic-looking skew.
EMAIL_DOMAINS = ("example.com", "example.com", "example.com", "example.in", "example.org", "example.net", "mail.example.com",
                 "inbox.example.in")
FATHER_FIRST = {"IN": ("Ramesh", "Suresh", "Prakash", "Rajendra", "Anil", "Sunil", "Ashok", "Mahesh", "Dilip", "Jagdish",
                       "Bijay", "Krishna", "Mohan", "Abdul", "Joseph", "Gurmeet")}

INTERESTS = (
    "Reading non-fiction", "Badminton", "Cricket", "Trekking", "Photography", "Cooking", "Chess", "Running half-marathons",
    "Playing the tabla", "Carnatic music", "Sketching", "Volunteering at an animal shelter", "Cycling", "Yoga", "Travelling",
    "Football", "Gardening", "Blogging", "Table tennis", "Swimming", "Listening to music", "Watching documentaries",
)
