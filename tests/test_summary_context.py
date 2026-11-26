import asyncio
import unittest

from sudoai.ai import checked_refs, summarize


class SummaryContextTests(unittest.TestCase):
    def test_wrapped_screen_context_preserves_original_citation_evidence(self):
        # Exact visual wrapping observed in the physical Pixel fixture, invented facts only.
        original = (
            "SudoAI Pixel screen fixture\nMorgan owns the network\nchecklist.\n"
            "Review is Friday at ten.\nCodeword: amber\nlighthouse 47\nInvented test facts only"
        )
        row = {
            "id": "screen-evidence",
            "session_id": "fixture-meeting",
            "kind": "screen",
            "speaker": "Screen",
            "text": original,
            "start_ms": 15179,
        }
        speech = {
            **row,
            "id": "speech-evidence",
            "kind": "speech",
            "text": "First speech line.\nSecond speech line.",
        }
        calls = []

        class InputCheckingProvider:
            async def structured(self, name, schema, instructions, payload):
                calls.append((name, instructions, payload))
                return {
                    "actions": [],
                    "decisions": [],
                    "highlights": [
                        {
                            "text": "Morgan owns the network checklist.",
                            "owner": "Morgan",
                            "due": None,
                            "references": [
                                {
                                    "id": row["id"],
                                    "quote": "Morgan owns the network\nchecklist.",
                                }
                            ],
                        },
                        {
                            "text": "The codeword is amber lighthouse 47.",
                            "owner": None,
                            "due": None,
                            "references": [
                                {
                                    "id": row["id"],
                                    "quote": "Codeword: amber\nlighthouse 47",
                                }
                            ],
                        },
                        {
                            "text": "An unsupported invented finding",
                            "owner": None,
                            "due": None,
                            "references": [
                                {"id": row["id"], "quote": "The codeword is blue"}
                            ],
                        },
                    ],
                }, {"input_tokens": 1, "output_tokens": 1}

        result, _ = asyncio.run(summarize(InputCheckingProvider(), [row, speech]))
        self.assertEqual(len(calls), 1)
        name, instructions, payload = calls[0]
        self.assertEqual(name, "meeting_summary")
        self.assertEqual(payload[0]["source_kind"], "screen")
        self.assertEqual(payload[0]["text"], " ".join(original.split()))
        self.assertEqual(row["text"], original)
        self.assertEqual(payload[1]["text"], speech["text"])
        self.assertEqual(speech["text"], "First speech line.\nSecond speech line.")
        self.assertIn("complete semantic finding", instructions)
        self.assertIn("not automatically a decision or action", instructions)
        self.assertEqual(len(result["highlights"]), 2)
        self.assertEqual(result["decisions"], [])
        self.assertEqual(
            result["highlights"][0]["references"][0]["quote"],
            "Morgan owns the network\nchecklist.",
        )
        self.assertEqual(result["highlights"][1]["references"][0]["at_ms"], 15179)
        normalized = checked_refs(
            [{"id": row["id"], "quote": "Morgan owns the network checklist."}],
            {row["id"]: row},
        )
        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["at_ms"], 15179)
        # Reflow is interpretation guidance only. Unsupported or reordered quote text still fails.
        self.assertEqual(
            checked_refs(
                [{"id": row["id"], "quote": "Morgan owns the checklist network"}],
                {row["id"]: row},
            ),
            [],
        )
