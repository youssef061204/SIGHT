# Existing portfolio publication evidence

The existing portfolio repository was updated at commit `1ebbc81dce7c02f89b7299e166a8dbd64ab836cf` and deployed through its existing Vercel Git integration. [Public SIGHT case study](https://youssefelsokkary.vercel.app/projects/sight).

[Deployment and checks](deployment.json) records lint, typecheck, production build, links, deployment status, public asset hashes and verification limits. [Local browser results](local/summary.json) and [public browser results](production/summary.json) cover desktop 1440×1000 and mobile 390×844, image decoding, actual video playback, repository links, console errors and horizontal overflow. The production folder contains genuine browser screenshots.

`verify.mjs` is the exact script used in this workspace. Its Playwright import and output paths describe the original local tooling; adapt those paths to rerun elsewhere. No authenticated browser state or credentials are included. Public HTTP and byte-level checks are also recorded in [portfolio-publication.json](../product-validation/portfolio-publication.json).

These checks establish web delivery and presentation. They do not establish Android field performance or assistive usability.
