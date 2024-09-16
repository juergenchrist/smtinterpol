# SMT-proof visualisation
Proof visualisation for given SMT-Solver

Manual:

When executing the visualize_proof() function by pushing the "Start SMT proof" button the proof text
that was put or copied into the the responding field will be split through the smt_parser() function.

In addition the class Node is created whose objects later contain following information:
unique IDs, arrays containing the children, ID of the parent node, complete text part of the node or leaf,
the filtered literals, pivots and resolved intermediate results for nodes that have children.

The smt_parser() function differentiates between inner nodes, starting with "(! (@res" or "(@res" and leafs,
starting with "(! (@clause", "(@clause)", "(! (@lemma", or "(@lemma".
Both will then be edited again with separate parsers (parseNode and parseLeaf), turned into a object of
the Node class and receive following information:
- unique IDs (starting at "0")
- an array with the responding kids (if present)
- ID of the parent node (root node keeps the parent ID "-1")
- a boolean "is_leaf" which will be set to "true" if it is a leaf and has no children
- a set with literals (sets being used so there are no duplicates)
- the pivot (if present)
and inner nodes will also receives a set of intermediate results of the resolved children.

Therefore the literals will be extracted with the extract_literals() function and then saved in node_literals.
This function calls the extract_literals_lemma() function on leafs containing the string "lemma" and the
extract_literals_clause() function on those containing the string "clause".
During this process the literals with parenthesis as well as those without are being considered.

If the string ":pivot" is also contained after these steps, the extract_pivot() function will be
executed to extract the corresponding pivot.

For the inner nodes these steps will be executed recursively and the compute_literals_res_node() function will
be executed which calculates and returns the resolved result of all children and saves it in node_literals.
In this function the compute_res() function always computes a resolve step from the children and saves
it in these children nodes as intermediate_result.

When resolving, the given pivot will be checked in normal and negated version.

In the end the proof tree results in an empty set.
