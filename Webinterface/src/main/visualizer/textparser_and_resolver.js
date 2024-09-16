// Set global counters and the global array for the nodes that will be made into a tree.
let root_node;
let all_nodes;
let node_counter;
let text_pos;
let text;

// Class for nodes and leafs with ids, the text in each node/leaf, ids of parents and children
// (the root node has parent_id = -1) and the info if it is a leaf or not.
class Node {
  constructor() {
    this.node_id = node_counter;
    this.node_text = [];
    this.children = [];
    this.parent_id = -1;
    this.is_leaf = false;
    this.node_literals = new Set();
    this.node_pivot = "";
    this.is_lemma_node = false;
    this.is_clause_node = false;
    // Intermediate_result is the resolution of the current nodes children with their literals and pivots.
    this.intermediate_result = new Set();
  }
  toString() {
    return "[" + this.node_id + ", " + this.node_text + ", [" + this.children + "], " + this.parent_id + ", \n" + this.is_leaf + ", {" + Array.from(this.node_literals) + "}, \n" + this.node_pivot + ", " + Array.from(this.intermediate_result) + "]";
  }
}

// The next extract-functions are for extracting the literals and pivots from their respective node_texts.

// Extracting the literals from the lemma-string so that they can be
// resolved with the other literals later on.
function extract_literals_lemma(lemma) {
  let literals = new Set();
  let pivot = "";
  let lemma_text_pos = 0;
  let lemma_literal = "";
  let open = 0;
  let close = 0;
  while (lemma_text_pos < lemma.length) {
    if (lemma.startsWith("or", lemma_text_pos)) {
      while (lemma_text_pos < lemma.length) {
        if (lemma[lemma_text_pos] == '(') {
          open++;
        }
        if (open > close) {
          lemma_literal += lemma[lemma_text_pos];
        }
        if (lemma[lemma_text_pos] == ')') {
          close++;
        }
        if (open === close && lemma_literal !== "") {
          literals.add(lemma_literal);
          lemma_literal = "";
          if (lemma.startsWith(") :", lemma_text_pos + 1)) {
            lemma_text_pos = lemma.length;
          }
        }
        lemma_text_pos++;
      }
    }
    lemma_text_pos++;
  }
  return literals;
}

// Extracting the pivot from the given clause or lemma
function extract_pivot(text) {
  let pivot = "";
  let open = 0;
  let close = 0;
  let text_pos = 0;
  let splitted_text = text.split(":pivot ")[1];
  // If pivot is in parenthesis
  if (splitted_text[text_pos] == "(") {
    while (text_pos < splitted_text.length) {
      if (splitted_text[text_pos] == '(') {
        open++;
      }
      if (open > close) {
        pivot += splitted_text[text_pos];
      }
      if (splitted_text[text_pos] == ')') {
        close++;
      }
      if (open === close) {
        break;
      }
      text_pos++;
    }
  } else { // Pivot without parenthesis
    while (text_pos < splitted_text.length) {
      if (splitted_text[text_pos] == ')') {
        break;
      }
      pivot += splitted_text[text_pos];
      text_pos++;
    }
  }
  return pivot;
}

function get_parenthesised_clause_literal(clause, text_position) {
  let clause_literal = "";
  let open = 0;
  let close = 0;
  while (text_position < clause.length) {
    if (clause[text_position] == '(') {
      open++;
    }
    if (open > close) {
      clause_literal += clause[text_position];
    }
    if (clause[text_position] == ')') {
      close++;
    }
    if (open === close) {
      if (clause_literal == "") {
        throw new Error("Literal is empty.");
      }
      return clause_literal;
    }
    text_position++
  }
}

function get_non_parenthesised_clause_literal(clause, text_position) {
  let clause_literal = "";
  while (text_position < clause.length) {
    if (clause[text_position] == ")" || clause[text_position] == " ") {
      if (clause_literal == "") {
        throw new Error("Literal is empty.");
      }
      return clause_literal;
    }
    if (clause[text_position] !== "") {
      clause_literal += clause[text_position];
    }
    text_position++;
  }
}

// Extracting the literals from the clause-string so that they can be
// resolved with the other literals later on.
function extract_literals_clause(clause) {
  let literals = new Set();
  let pivot = "";
  let clause_text_pos = 0;
  let open = 0;
  let close = 0;
  let is_parenthesis_ignored = false;
  while (clause_text_pos < clause.length) {
    // Start after "(@clause " or "(! (@clause "
    if (clause.startsWith("@clause ", clause_text_pos)) {
      clause_text_pos += 8;
      // Completely ignore the next parenthesis
      if (is_parenthesis_ignored == false) {
        if (clause[clause_text_pos] == "(") {
          open++;
          clause_text_pos++;
          while (open > close) {
            if (clause[clause_text_pos] == '(') {
              open++;
            }
            if (clause[clause_text_pos] == ')') {
              close++;
            }
            if (open === close) {
              break;
            }
            clause_text_pos++;
          }
          is_parenthesis_ignored = true;
          clause_text_pos += 5; // Ignore " (! " that comes right after the to be ignored parenthesis
        }
      }
    }
    if (is_parenthesis_ignored) {
      // If there are at least 2 literals then there is alway an "(or "
      if (clause.startsWith("(or ", clause_text_pos)) {
        clause_text_pos += 4; // Jump behind "(or "
        while (clause_text_pos < clause.length) {
          if (clause.startsWith(" :input", clause_text_pos)) {
            clause_text_pos = clause.length;
            break
          }
          // If the literal is in parenthesis
          if (clause[clause_text_pos] == "(") {
            let clause_literal = get_parenthesised_clause_literal(clause, clause_text_pos);
            literals.add(clause_literal);
            clause_text_pos += (clause_literal.length);
          } // If the literal is without parenthesis
          else if (clause[clause_text_pos] !== "(" && clause[clause_text_pos] !== " ") {
            let clause_literal = get_non_parenthesised_clause_literal(clause, clause_text_pos);
            literals.add(clause_literal);
            clause_text_pos += (clause_literal.length);
          }
          clause_text_pos++;
        }
      }
      // If there is no "(or " then there is only one literal.
      // For a literal with parenthesis:
      else if (clause[clause_text_pos] == "(") {
        let clause_literal = get_parenthesised_clause_literal(clause, clause_text_pos);
        literals.add(clause_literal);
        clause_text_pos += (clause_literal.length);
        if (clause.startsWith(" :input", clause_text_pos)) {
          clause_text_pos = clause.length;
          break;
        }
      } else if (clause[clause_text_pos] !== " " || clause[clause_text_pos] !== "") {
        //  For a literal without parenthesis:
        let clause_literal = get_non_parenthesised_clause_literal(clause, clause_text_pos);
        literals.add(clause_literal);
        clause_text_pos += (clause_literal.length);
        if (clause.startsWith(" :input", clause_text_pos)) {
          clause_text_pos = clause.length;
          break;
        }
      }
    }
    clause_text_pos++;
  }
  return literals;
}

function extract_literals(text, node) {
  if (text.includes("lemma")) {
    node.is_lemma_node = true;
    return extract_literals_lemma(text);
  }
  if (text.includes("clause")) {
    node.is_clause_node = true;
    return extract_literals_clause(text);
  }
}

// A function for parsing the text for the leafs of the (syntax) tree that will be created later.
function parseLeaf() {
  let newStr = [],
    open = 0,
    close = 0;
  // Beginning of new leaf found, so start new "node" with id number and empty text.
  // Child and parent ids will be recursively added later.
  let node = new Node();
  node.is_leaf = true;
  node_counter++;
  // Count the brackets to see if the expression is completed and save the text in newStr.
  while (text_pos < text.length) {
    if (text[text_pos] == '(') {
      open++;
    }
    if (open > close) {
      newStr += text[text_pos];
    }
    if (text[text_pos] == ')') {
      close++;
    }
    text_pos++;
    if (open === close) {
      break;
    }
  }
  // Assign the found text to the newly created leaf.
  node.node_text = newStr;
  node.node_literals = extract_literals(newStr, node);
  if (newStr.includes(":pivot")) {
    node.node_pivot = extract_pivot(newStr);
  }
  all_nodes.push(node);
  return node;
}

// A function for parsing the text for the nodes of the (syntax) tree that will be created later.
function parseNode() {
  let newStr = [],
    open = 0,
    close = 0;
  // Beginning of new leaf found, so start new "node" with id number and empty text.
  // Child and parent ids will be added later.
  let node = new Node();
  node.is_leaf = false;
  node_counter++;
  // The starting text of the proof, where a node is found can directly be added to the
  // created node-object and therefore the found opening brackets can already be counted
  // and also the text position needs to be adjusted.
  if (text.startsWith('(@res', text_pos)) {
    open++;
    text_pos += 6;
    newStr += "(@res ";
  } else if (text.startsWith('(! (@res ', text_pos)) {
    open += 2;
    text_pos += 9;
    newStr += "(! (@res ";
  }
  while (text_pos < text.length) {
    // If another keyword is found inside the res-node (so if there are children)
    // we recursively go through these children first and get back to the parent(s) afterwards.
    // Here the current node_id needs to be set to the next child's parent_id
    // and the child_id of the "node" needs to be filled with the ids of all children.
    if (text.startsWith("(! (@res", text_pos) || text.startsWith("(@res", text_pos) || text.startsWith("(! (@clause", text_pos) || text.startsWith("(! (@lemma", text_pos) || text.startsWith("(@clause", text_pos) || text.startsWith("(@lemma", text_pos)) {
      let child = smt_parser();
      child.parent_id = node.node_id;
      node.children.push(child);
      newStr += child.node_id;
    }
    // Count the brackets to see if the expression is completed and save the text in newStr.
    if (text[text_pos] == '(') {
      open++;
    }
    if (open > close) {
      newStr += text[text_pos];
    }
    if (text[text_pos] == ')') {
      close++;
    }
    text_pos++;
    if (open === close) {
      break;
    }
  }
  // Save the resolved result of the nodes children in intermediate_result.
  // This will be used later for building up our (syntax) tree.
  node.node_literals = compute_literals_res_node(node.children);
  if (newStr.includes(":pivot ")) {
    node.node_pivot = extract_pivot(newStr);
  }
  // Assign the found text to the newly created node.
  node.node_text += newStr;
  all_nodes.push(node);
  return node;
}

// The complete parser for leafs and nodes.
function smt_parser() {
  for (; text_pos < text.length; text_pos++) {
    if (text.startsWith("(! (@res", text_pos) || text.startsWith("(@res", text_pos)) {
      return parseNode();
    } else if (text.startsWith("(! (@clause", text_pos) || text.startsWith("(! (@lemma", text_pos) || text.startsWith("(@clause", text_pos) || text.startsWith("(@lemma", text_pos)) {
      return parseLeaf();
    }
  }
  // If there is no proof text, we return an empty node.
  let empty_node = new Node();
  return empty_node;
}

// Recursively compute the literals for the res-nodes and their children.
function compute_literals_res_node(children) {
  let left_clause = Array.from(children[0].node_literals);
  let right_clause = Array.from(children[1].node_literals);
  let child_pivot = children[1].node_pivot;
  for (var i = 1; i < children.length; i++) {
    children[i].intermediate_result = compute_res(left_clause, right_clause, child_pivot);
    left_clause = Array.from(children[i].intermediate_result);
    if (children[i + 1]) {
      right_clause = Array.from(children[i + 1].node_literals);
      child_pivot = children[i + 1].node_pivot;
    }
  }
  return new Set(left_clause);
}

// Take the given pivot and negate it.
// So either put a "(not )" around the pivot or just take the inner part of the
// "(not (pivot))" string.
function negate_pivot(pivot) {
  let negated_pivot;
  if (pivot.startsWith("(not")) {
    negated_pivot = pivot.slice(5, -1);
  } else {
    negated_pivot = "(not " + pivot + ")";
  }
  return negated_pivot;
}

// Compute the resolution of 2 clauses and a pivot.
// pivot_found = 0 (pivot not found), = 1 (pivot found), = -1 (negated pivot found)
function compute_res(resolve_literals_1, resolve_literals_2, resolve_pivot) {
  let resolution = new Set();
  let pivot_found = 0;
  let resolve_literals_1_array = Array.from(resolve_literals_1);
  let resolve_literals_2_array = Array.from(resolve_literals_2);
  let negated_resolve_pivot = negate_pivot(resolve_pivot)
  for (var i = 0; i < resolve_literals_1_array.length; i++) {
    if (resolve_literals_1_array[i] === resolve_pivot) {
      pivot_found = 1;
    } else if (resolve_literals_1_array[i] === negated_resolve_pivot) {
      pivot_found = -1;
    } else {
      resolution.add(resolve_literals_1_array[i]);
    }
  }
  if (pivot_found === 0) {
    throw new Error("There is neither a pivot nor a negated pivot found in our left clause.");
  }
  let opposite_pivot = false;
  for (var i = 0; i < resolve_literals_2_array.length; i++) {
    if (pivot_found === 1 && resolve_literals_2_array[i] === negated_resolve_pivot) {
      opposite_pivot = true;
    } else if (pivot_found === -1 && resolve_literals_2_array[i] === resolve_pivot) {
      opposite_pivot = true;
    } else {
      resolution.add(resolve_literals_2_array[i]);
    }
  }
  if (opposite_pivot === false) {
    throw new Error("There is no negated pivot found in the right clause.");
  }
  return resolution;
}

// This will be the function for the complete visualization proof.
// It will parse through the given proof text and create a (syntax) tree.
function visualize_proof(input) {
  text_pos = 0;
  node_counter = 0;
  all_nodes = [];
  text = input;
  root_node = smt_parser();
  return all_nodes; // For testing
}

function create_tree(current_node, parent_id) {
  if (current_node.children.length !== 0) {
    let node_id = "node" + current_node.node_id;
    let current_pivot = check_pivot_availability(current_node);
    let current_literal = check_node_literal_lemma_clause(current_node);
    document.getElementById(parent_id).innerHTML += current_literal+current_pivot+
    '</span> <span class="intermediate_result">{'+Array.from(current_node.intermediate_result)+'}</span></span> <ul class="nested" id=' + node_id + '></ul></li>';
    for (var i = 0; i < current_node.children.length; i++) {
      create_tree(current_node.children[i], node_id);
    }
  } else if (current_node.children.length === 0) {
    let node_id = "node" + current_node.node_id;
    let current_pivot = check_pivot_availability(current_node);
    let current_literal = check_leaf_literal_lemma_clause(current_node);
    document.getElementById(parent_id).innerHTML += current_literal+current_pivot+
    '</span> <span class="intermediate_result">{'+Array.from(current_node.intermediate_result)+'}</span></li>';
  }
}

// Create a tree with the resolved literals so that the literals, pivots and
// intermediate results before each resolution step will be shown.
window.onload = function() {
  var visualize_button = document.getElementById("visualizer");
  visualize_button.addEventListener("click", function() {
    document.getElementById("mytree").innerHTML = '';
    document.getElementById("legend").style.display = "inline-block";
    visualize_button.innerHTML = "SMT proof started";
    let proof_input = document.getElementById("input_text").value;
    visualize_proof(proof_input);
    document.querySelector("button").style.color = "darkred";
    create_tree(root_node, "mytree");
    var toggler = document.getElementsByClassName("arrow");
    for (var i = 0; i < toggler.length; i++) {
      toggler[i].addEventListener("click", function() {
        this.parentElement.querySelector(".nested").classList.toggle("active");
        this.classList.toggle("arrow-down");
      });
    }
  });
}

// Check if pivot is available and either return it or just the bracket to close
// the node_literals array in the string for the html site
function check_pivot_availability(node) {
  if (node.node_pivot) {
    let str1 = '}</span> <span class="node_pivot">';
    let str2 = node.node_pivot;
    return str1+str2;
  } else {
    return "}";
  }
}

// Check if literal of the leaf contains @lemma or @clause to highlight it later
function check_leaf_literal_lemma_clause(node) {
  if (node.is_lemma_node) {
    return '<li><span class="lemma_literals">{' + Array.from(node.node_literals);
  } else if (node.is_clause_node) {
    return '<li><span class="clause_literals">{' + Array.from(node.node_literals);
  } else {
    return '<li><span class="inner_node_literals">{' + Array.from(node.node_literals);
  }
}

// Check if literal of the node contains @lemma or @clause to highlight it later
function check_node_literal_lemma_clause(node) {
  if (node.is_lemma_node) {
    return '<li><span class="arrow"><span class="lemma_literals">{' +Array.from(node.node_literals);
  } else if (node.is_clause_node) {
    return '<li><span class="arrow"><span class="clause_literals">{' +Array.from(node.node_literals);
  } else {
    return '<li><span class="arrow"><span class="inner_node_literals">{' +Array.from(node.node_literals);
  }
}
