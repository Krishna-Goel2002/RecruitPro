# Frontend — React TypeScript SPA

## AI-Powered Recruitment & Hiring Management Platform

This is the frontend client for the recruitment platform, built with React and TypeScript.

## Tech Stack

- **React 18+** with TypeScript
- **Vite** as build tool
- **React Router v6** for routing
- **Axios** for HTTP requests
- **CSS Modules** for styling (or a UI library TBD)

## Project Structure

```
src/
├── components/          # Reusable UI components
│   ├── common/          # Buttons, inputs, modals, cards
│   └── layout/          # Header, sidebar, footer, page layout
├── pages/               # Page-level components (one per route)
│   ├── auth/            # Login, Register pages
│   ├── dashboard/       # Role-based dashboards
│   ├── jobs/            # Job listing, job detail, job creation
│   ├── candidates/      # Candidate profile, resume upload
│   ├── applications/    # Application tracking
│   ├── assessments/     # Assessment taking and management
│   ├── interviews/      # Interview scheduling and feedback
│   └── analytics/       # Analytics dashboards
├── services/            # API client functions
├── hooks/               # Custom React hooks
├── context/             # React Context providers (Auth, Theme)
├── types/               # TypeScript type definitions
├── utils/               # Helper functions
├── assets/              # Static images, icons, fonts
└── styles/              # Global CSS, theme variables
```

## Setup (Phase 4+)

```bash
npm install
npm run dev
```

The dev server will start on `http://localhost:5173` (Vite default) or `http://localhost:3000`.

## Environment Variables

Create a `.env.local` file:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

---

> 🚧 The frontend will be initialized with Vite + React + TypeScript in Phase 4 (Candidate Portal).
> This README serves as the architectural placeholder.
