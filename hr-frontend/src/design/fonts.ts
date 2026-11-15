/*
 * Body and mono faces (DESIGN_SYSTEM §4), bundled locally from npm (the CSP forbids external fonts).
 * latin-ext files only download when a character in their unicode-range is used (e.g. ₹), thanks to @font-face
 * unicode-range, so first load stays at four woff2 files.
 */
import '@fontsource/ibm-plex-sans/latin-400.css'
import '@fontsource/ibm-plex-sans/latin-500.css'
import '@fontsource/ibm-plex-sans/latin-600.css'
import '@fontsource/ibm-plex-sans/latin-ext-400.css'
import '@fontsource/ibm-plex-sans/latin-ext-500.css'
import '@fontsource/ibm-plex-sans/latin-ext-600.css'
import '@fontsource/ibm-plex-mono/latin-400.css'
import '@fontsource/ibm-plex-mono/latin-500.css'
