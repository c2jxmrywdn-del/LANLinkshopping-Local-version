# -*- coding: utf-8 -*-
with open("ai-gateway/tests/test_tools.py", "r", encoding="utf-8") as f:
    c = f.read()

c = c.replace('"lanlink_query_knowledge",\n            "lanlink_track_logistics",',
              '"lanlink_query_knowledge",\n            "lanlink_search_knowledge",\n            "lanlink_track_logistics",')
c = c.replace('self.assertEqual(len(names), 13)', 'self.assertEqual(len(names), 14)')
c = c.replace('self.assertEqual(len(names), 10)', 'self.assertEqual(len(names), 14)')

with open("ai-gateway/tests/test_tools.py", "w", encoding="utf-8") as f:
    f.write(c)

with open("ai-gateway/tests/test_ai_tools_gateway.py", "r", encoding="utf-8") as f:
    c2 = f.read()

c2 = c2.replace('self.assertEqual(data.get("count"), 13)', 'self.assertEqual(data.get("count"), 14)')
c2 = c2.replace('self.assertEqual(mall_data.get("count"), 9)', 'self.assertEqual(mall_data.get("count"), 10)')
c2 = c2.replace('self.assertEqual(len(tools_list), 13)', 'self.assertEqual(len(tools_list), 14)')

with open("ai-gateway/tests/test_ai_tools_gateway.py", "w", encoding="utf-8") as f:
    f.write(c2)

print("Tests updated to 14 tools (10 mall + 4 builtin)!")
