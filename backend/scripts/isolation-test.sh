#!/bin/bash
# End-to-end multi-tenant isolation smoke test. Hits the running backend on :8083.
# Exits 0 if every assertion passes, non-zero otherwise.
set +e
API=http://localhost:8083/api
JQ() { node -e "const s=require('fs').readFileSync(0,'utf8');const o=JSON.parse(s);console.log(o$1)"; }
PASS=0; FAIL=0

section() { echo ""; echo "═══ $* ═══"; }
assert()  {
  local name="$1"; local expected="$2"; local actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  ✔ $name"; PASS=$((PASS+1))
  else
    echo "  ✘ $name — expected=$expected actual=$actual"; FAIL=$((FAIL+1))
  fi
}

# ---------- 1. Login all three roles ----------
section "Login"
S_JWT=$(curl -s -X POST $API/auth/login -H "Content-Type: application/json" -d '{"username":"superadmin","password":"superadmin123"}' | JQ ".token")
A_JWT=$(curl -s -X POST $API/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | JQ ".token")
assert "super JWT non-empty" "true" "$([ -n "$S_JWT" ] && echo true)"
assert "admin JWT non-empty" "true" "$([ -n "$A_JWT" ] && echo true)"
assert "bad password → 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $API/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')"

# ---------- 2. Super creates a fresh team ----------
section "Super creates fresh team"
# Unique slug per run so re-executing this suite always exercises a truly fresh team and
# doesn't inherit leftovers from an earlier session.
SLUG="legal$$"
LEGAL_ID=$(curl -s -X POST $API/super/teams -H "Content-Type: application/json" -H "Authorization: Bearer $S_JWT" -d "{\"slug\":\"$SLUG\",\"name\":\"Legal Team\",\"description\":\"Contract review\",\"color\":\"#f59e0b\"}" | JQ ".id")
assert "team created (id=$LEGAL_ID)" "true" "$([ -n "$LEGAL_ID" ] && [ "$LEGAL_ID" != "undefined" ] && echo true)"
assert "duplicate slug → 409" "409" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $API/super/teams -H 'Content-Type: application/json' -H "Authorization: Bearer $S_JWT" -d "{\"slug\":\"$SLUG\",\"name\":\"Dup\",\"color\":\"#000000\"}")"
assert "bad slug → 400" "400" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $API/super/teams -H 'Content-Type: application/json' -H "Authorization: Bearer $S_JWT" -d '{"slug":"UPPER!!","name":"X","color":"#000000"}')"

# ---------- 3. Super seeds an admin inside the new team via impersonation ----------
section "Super creates admin inside Legal team (impersonation)"
# fresh username each run to avoid the "user already exists" flake
LU="legal_admin_$$"
NEW_ADMIN=$(curl -s -X POST $API/admin/users -H "Content-Type: application/json" \
  -H "Authorization: Bearer $S_JWT" -H "X-Impersonate-Team-Id: $LEGAL_ID" \
  -d "{\"username\":\"$LU\",\"password\":\"legal_pass_123\",\"displayName\":\"Legal Admin\",\"role\":\"ADMIN\"}")
LEGAL_ADMIN_ID=$(echo "$NEW_ADMIN" | JQ ".id")
assert "$LU created" "true" "$([ -n "$LEGAL_ADMIN_ID" ] && [ "$LEGAL_ADMIN_ID" != "undefined" ] && echo true)"

# ---------- 4. Log in as new admin, verify team assignment ----------
section "New admin login"
L_JWT=$(curl -s -X POST $API/auth/login -H "Content-Type: application/json" -d "{\"username\":\"$LU\",\"password\":\"legal_pass_123\"}" | JQ ".token")
assert "$LU login → JWT" "true" "$([ -n "$L_JWT" ] && echo true)"
ME=$(curl -s $API/auth/me -H "Authorization: Bearer $L_JWT")
assert "$LU is in Legal team" "Legal Team" "$(echo $ME | JQ '.team.name')"

# ---------- 5. Each admin creates a project ----------
section "Admins each create a project"
PA_NAME="AAA-only-$$"
PL_NAME="LLL-only-$$"
P_A=$(curl -s -X POST $API/admin/projects -H "Content-Type: application/json" -H "Authorization: Bearer $A_JWT" -d "{\"name\":\"$PA_NAME\",\"subtitle\":\"general\"}" | JQ ".id")
P_L=$(curl -s -X POST $API/admin/projects -H "Content-Type: application/json" -H "Authorization: Bearer $L_JWT" -d "{\"name\":\"$PL_NAME\",\"subtitle\":\"legal\"}" | JQ ".id")
assert "general project created (id=$P_A)" "true" "$([ -n "$P_A" ] && [ "$P_A" != "undefined" ] && echo true)"
assert "legal project created (id=$P_L)" "true" "$([ -n "$P_L" ] && [ "$P_L" != "undefined" ] && echo true)"

# ---------- 6. Cross-team read isolation ----------
section "Cross-team read isolation"
G_LIST=$(curl -s $API/admin/projects -H "Authorization: Bearer $A_JWT" | JQ ".map(p=>p.id).join(',')")
L_LIST=$(curl -s $API/admin/projects -H "Authorization: Bearer $L_JWT" | JQ ".map(p=>p.id).join(',')")
assert "General sees own project" "true" "$(echo ",$G_LIST," | grep -q ",$P_A," && echo true)"
assert "General does NOT see Legal's project" "" "$(echo ",$G_LIST," | grep -o ",$P_L,")"
assert "Legal sees own project" "true" "$(echo ",$L_LIST," | grep -q ",$P_L," && echo true)"
assert "Legal does NOT see General's project" "" "$(echo ",$L_LIST," | grep -o ",$P_A,")"

# ---------- 7. Cross-team write isolation ----------
section "Cross-team write isolation"
assert "Legal PATCH General's project → 404" "404" "$(curl -s -o /dev/null -w '%{http_code}' -X PATCH $API/admin/projects/$P_A -H 'Content-Type: application/json' -H "Authorization: Bearer $L_JWT" -d '{"name":"HIJACK"}')"
assert "Legal DELETE General's project → 404" "404" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $API/admin/projects/$P_A -H "Authorization: Bearer $L_JWT")"

# ---------- 8. User list isolation ----------
section "User list isolation"
G_USERS=$(curl -s $API/admin/users -H "Authorization: Bearer $A_JWT" | JQ ".map(u=>u.username).join(',')")
L_USERS=$(curl -s $API/admin/users -H "Authorization: Bearer $L_JWT" | JQ ".map(u=>u.username).join(',')")
assert "General sees admin (self)" "true" "$(echo ",$G_USERS," | grep -q ',admin,' && echo true)"
assert "General does NOT see $LU" "" "$(echo ",$G_USERS," | grep -o ",$LU,")"
assert "General does NOT see superadmin" "" "$(echo ",$G_USERS," | grep -o ',superadmin,')"
assert "Legal sees $LU (self)" "true" "$(echo ",$L_USERS," | grep -q ",$LU," && echo true)"
assert "Legal does NOT see admin" "" "$(echo ",$L_USERS," | grep -o ',admin,')"

# ---------- 9. Departments per-team ----------
section "Departments per-team"
DL=$(curl -s $API/admin/departments -H "Authorization: Bearer $L_JWT" | JQ ".length")
assert "Legal starts with 0 departments" "0" "$DL"
# API requires projectId on create — use the projects the two admins just made.
DPNAME="CompX-$$"
DA_NEW=$(curl -s -X POST $API/admin/departments -H "Content-Type: application/json" -H "Authorization: Bearer $A_JWT" -d "{\"name\":\"$DPNAME\",\"projectId\":$P_A,\"active\":true}" | JQ ".id")
DL_NEW=$(curl -s -X POST $API/admin/departments -H "Content-Type: application/json" -H "Authorization: Bearer $L_JWT" -d "{\"name\":\"$DPNAME\",\"projectId\":$P_L,\"active\":true}" | JQ ".id")
assert "General created dept $DPNAME" "true" "$([ -n "$DA_NEW" ] && [ "$DA_NEW" != "undefined" ] && echo true)"
assert "Legal ALSO created dept $DPNAME (per-team uniqueness)" "true" "$([ -n "$DL_NEW" ] && [ "$DL_NEW" != "undefined" ] && echo true)"

# ---------- 10. Super admin cross-team visibility ----------
section "Super admin cross-team visibility"
S_PROJECTS=$(curl -s $API/admin/projects -H "Authorization: Bearer $S_JWT" | JQ ".length")
assert "Super sees ALL projects (>= 2)" "true" "$([ "$S_PROJECTS" -ge 2 ] && echo true)"
S_USERS=$(curl -s $API/admin/users -H "Authorization: Bearer $S_JWT" | JQ ".length")
assert "Super sees ALL users (>= 3)" "true" "$([ "$S_USERS" -ge 3 ] && echo true)"
TEAMS_COUNT=$(curl -s $API/super/overview -H "Authorization: Bearer $S_JWT" | JQ ".teams.length")
assert "Super overview lists all teams (>= 2)" "true" "$([ "$TEAMS_COUNT" -ge 2 ] && echo true)"

# ---------- 11. Super impersonation scoping ----------
section "Super impersonation scoping"
assert "super enters Legal team → 200" "200" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $API/super/teams/$LEGAL_ID/enter -H "Authorization: Bearer $S_JWT")"
S_AS_LEGAL=$(curl -s $API/admin/projects -H "Authorization: Bearer $S_JWT" -H "X-Impersonate-Team-Id: $LEGAL_ID" | JQ ".map(p=>p.id).join(',')")
assert "Super impersonating Legal sees Legal's project" "true" "$(echo ",$S_AS_LEGAL," | grep -q ",$P_L," && echo true)"
assert "Super impersonating Legal does NOT see General's" "" "$(echo ",$S_AS_LEGAL," | grep -o ",$P_A,")"

# ---------- 12. Role gating ----------
section "Role gating"
assert "team admin blocked from /super/* → 403" "403" "$(curl -s -o /dev/null -w '%{http_code}' $API/super/teams -H "Authorization: Bearer $A_JWT")"
assert "unauthenticated /super → 403" "403" "$(curl -s -o /dev/null -w '%{http_code}' $API/super/teams)"

# ---------- 13. Cannot delete non-empty team ----------
section "Delete team safety"
assert "delete team with users → 409" "409" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $API/super/teams/$LEGAL_ID -H "Authorization: Bearer $S_JWT")"

echo ""
echo "════════════════════════════════════════════"
echo "  PASS: $PASS   FAIL: $FAIL"
echo "════════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
