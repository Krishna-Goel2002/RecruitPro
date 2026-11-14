import React from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { isApiError } from './api/client'
import { AuthProvider } from './auth/AuthContext'
import { NoticeProvider } from './components/Notices'
import './styles/base.css'
import './styles/workspace.css'
import './styles/components.css'
import './styles/pages.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: false,
      // Never retry client errors (403/404/409…); retry transient failures once.
      retry: (failureCount, error) => !(isApiError(error) && error.status >= 400 && error.status < 500) && failureCount < 1,
    },
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <NoticeProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </NoticeProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
)
