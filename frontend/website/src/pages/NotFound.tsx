import { Link } from 'react-router-dom'
import Page from '../components/Page'

export default function NotFound() {
  return (
    <Page title="Page not found">
      <p>
        The page you are looking for does not exist. <Link to="/">Go to the home page</Link>.
      </p>
    </Page>
  )
}
