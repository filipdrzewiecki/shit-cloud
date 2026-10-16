import { Route, Routes } from 'react-router-dom'
import { AppHostPage } from './pages/AppHostPage'
import { AppListPage } from './pages/AppListPage'
import { BuildPage } from './pages/BuildPage'
import { UploadPage } from './pages/UploadPage'
import { Layout } from './shell/Layout'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<AppListPage />} />
        <Route path="apps/:slug" element={<AppHostPage />} />
        <Route path="build" element={<BuildPage />} />
        <Route path="upload" element={<UploadPage />} />
        <Route path="*" element={<p className="muted">Nothing here.</p>} />
      </Route>
    </Routes>
  )
}
