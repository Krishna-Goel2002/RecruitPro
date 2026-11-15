// Design system first: it declares the cascade-layer order every later stylesheet slots into.
import './design/fonts'
import './design/index.css'
import './styles/legacy.css'
import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { initTheme } from './design/theme'

initTheme()

const root = document.getElementById('root')
if (!root) throw new Error('Missing #root element')

createRoot(root).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
