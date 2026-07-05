import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


class ProviderNetworkPolicyContractTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (REPO_ROOT / relative_path).read_text(encoding="utf-8")

    def test_endpoint_inventory_has_concrete_backoff_and_fallback_language(self):
        policy = json.loads(self.read("docs/security/network-endpoints.json"))
        forbidden = (
            "no host-specific backoff",
            "no app-specific backoff",
            "no retry/backoff",
            "source metrics record failures",
        )

        for endpoint in policy["endpoints"]:
            rate_limit = endpoint.get("rateLimitCachePolicy", "")
            fallback = endpoint.get("fallbackBehavior", "")
            self.assertTrue(rate_limit.strip(), endpoint["id"])
            self.assertTrue(fallback.strip(), endpoint["id"])
            combined = f"{rate_limit} {fallback}"
            for phrase in forbidden:
                self.assertNotIn(phrase, combined, endpoint["id"])

    def test_provider_policy_model_exposes_active_diagnostic_fields(self):
        source = self.read("app/src/main/java/com/freevibe/data/model/ProviderNetworkPolicy.kt")

        for field in (
            "timeoutPolicy",
            "backoffPolicy",
            "cacheFallbackPolicy",
            "disabledBehavior",
        ):
            self.assertIn(f"val {field}: String", source)
            self.assertIn(f"{field}.isNotBlank()", source)

        self.assertIn('"timeout $timeoutPolicy"', source)
        self.assertIn('"backoff $backoffPolicy"', source)
        self.assertIn('"fallback $cacheFallbackPolicy"', source)
        self.assertIn('"disabled $disabledBehavior"', source)


if __name__ == "__main__":
    unittest.main()
