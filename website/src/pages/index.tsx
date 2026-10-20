import React from 'react';
import { Redirect } from '@docusaurus/router';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

/**
 * The site root lands on the Quick Start — the quick, no-choices path to
 * install babybuddy-next and take it for a test drive (Immich-style front
 * door). No 404 at `/`.
 */
export default function Home(): JSX.Element {
  const { siteConfig } = useDocusaurusContext();
  return <Redirect to={useBaseUrl('/overview/quick-start')} />;
}
