// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui

object Links {
  // Victorious Mesh: Tailscale-hosted links replaced. DEFAULT_CONTROL_URL is kept
  // only so upstream code that compares against it keeps working; the app never
  // logs in there unless the user explicitly types it as their control server.
  const val DEFAULT_CONTROL_URL = "https://controlplane.tailscale.com"
  const val SERVER_URL = "https://headscale.net"
  const val ADMIN_URL = SERVER_URL
  const val SIGNIN_URL = SERVER_URL
  const val REPO_URL = "https://github.com/Victorious93/victorious-mesh"
  const val PRIVACY_POLICY_URL = REPO_URL
  const val TERMS_URL = REPO_URL
  const val DOCS_URL = "https://headscale.net/stable/"
  const val START_GUIDE_URL = DOCS_URL
  const val LICENSES_URL = "$REPO_URL/blob/main/LICENSE"
  const val DELETE_ACCOUNT_URL = REPO_URL
  const val TAILNET_LOCK_KB_URL = DOCS_URL
  const val KEY_EXPIRY_KB_URL = DOCS_URL
  const val INSTALL_TAILSCALE_KB_URL = REPO_URL
  const val INSTALL_UNSTABLE_KB_URL = REPO_URL
  const val MAGICDNS_KB_URL = "https://headscale.net/stable/ref/dns/"
  const val TROUBLESHOOTING_KB_URL = DOCS_URL
  const val SUPPORT_URL = "$REPO_URL/issues"
  const val TAILDROP_KB_URL = DOCS_URL
  const val TAILFS_KB_URL = DOCS_URL
  const val SUBNET_ROUTERS_KB_URL = "https://headscale.net/stable/ref/routes/"
}
