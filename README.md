# replicant
[![Build Status](https://travis-ci.org/Kambius/replicant.svg?branch=master)](https://travis-ci.org/Kambius/replicant)

Distributed key-value storage

## HDML
HDML stands for Hierarchical Data Merge Language. It is used to resolve data consistency conflicts in distributed system. This language basically describes how to merge set of JSONs with associated timestamp into one document.

Here is some example:
```
// definition of simple function
func add(a, b): a + b

func max(a, b):
  if (a < b) than b else a

// it also supports json literals
func complexSquare(x, y): {
  "width": max(x.width, y.width),
  "height": max(x.height, y.height),
  "square": max(x.width, y.width) * max(x.height, y.height)
}

/* definition which describes
   merge process */
merge fooMerge:
  $.aggKey.$fold(0, add),
  $.maxKey.$fold(0, max),
  $.shape.$fold({"width": 0, "height": 0, "square": 0}, complexSquare)

// entry point
merge main:
  $.foo.$fooMerge,
  $.timestampKey.$timestamp,
  $.*.$random
```

Lets assume we have following data to be merged:
```
// timestamp = 1
{
  "foo": {
     "aggKey": 2,
     "maxKey": 7,
     "shape": {
       "width": 3,
       "height": 1,
       "square": 3
     }
   },
   "timestampKey": "somestr",
   "defaultValue": [2, 4]
}

// timestamp = 2
{
  "foo": {
     "aggKey": 4,
     "maxKey": 2,
     "shape": {
       "width": 3,
       "height": 2,
       "square": 6
     }
   }
}

// timestamp = 3
{
  "foo": {
     "aggKey": 7,
     "maxKey": 1,
     "shape": {
       "width": 7,
       "height": 1,
       "square": 7
     }
   },
   "timestampKey": "newstr",
   "defaultValue": []
}
```
Running this program with data mentioned above will result in a following result:
```
// timestamp = 3
{
  "foo" : {
    "aggKey": 13.0,
    "maxKey": 7.0,
    "shape": {
      "width": 7.0,
      "height": 2.0,
      "square": 14.0
    }
  },
  "timestampKey": "newstr",
  "defaultValue": null
}
```

As you can see we can describe any rules for merging data.

Program consists of two types of definitions: merges and funcs. Func is a regular function. You can call them in C-like style or using `$`-notation. `a.b.$c(1, 3)` is an equivalent of C-like  `c(a.b, 1, 3)`. This notation was added for convenient use of nested fields. You can omit parameters list if it's empty. Merges describe how to merge json field by field. 

Syntax is fully described in BNF notation:
```
<zero>            ::= "0"
<non-zero-digit>  ::= "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"
<digit>           ::= <zero> | <non-zero-digit>
<digits>          ::= <digit> | <digit> <digits>
<number>          ::= <zero> | <non-zero-digit> | <non-zero-digit> <digits>
<int>             ::= <number>
<float>           ::= <number> "." <number>

<letter>          ::= "A" | "B" | "C" | "D" | "E" | "F" | "G" | "H" | "I" | 
                      "J" | "K" | "L" | "M" | "N" | "O" | "P" | "Q" | "R" | 
                      "S" | "T" | "U" | "V" | "W" | "X" | "Y" | "Z" | "a" | 
                      "b" | "c" | "d" | "e" | "f" | "g" | "h" | "i" | "j" | 
                      "k" | "l" | "m" | "n" | "o" | "p" | "q" | "r" | "s" | 
                      "t" | "u" | "v" | "w" | "x" | "y" | "z"
<symbol>          ::= "~!@#$%^&*(),./\\|`';:\""
<whitespace>      ::= "    "
<char>            ::= <letter> | <symbol> | <whitespace>
<chars>           ::= <char> | <char> <chars> 
<string>          ::= "\"" <chars> "\""       

<bool>            ::= "true" | "false"
<null>            ::= "null"
<none>            ::= "none"
<type>            ::= "int" | "float" | "string" | "bool" | "null" | "none" | "object" | "list"

<key>             ::= <identifier>
<key-value>       ::= <key> ":" <value>
<key-values>      ::= <key-value> | <key-value> "," <key-values>
<object>          ::= "{}" | "{" <key-values> "}"
<values>          ::= <value> | <value> "," <values>
<list>            ::= "[]" | "[" <values> "]"

<identifier>      ::= <letter> | <letter> <identifier>
<literal>         ::= <int> | <float> | <string> | <bool> | <null> | <none> | <object> | <list> | <type> | <error>
<error>           ::= "error" <value>
<binary-operator> ::= "+" | "-" | "*" | "/"  | "==" | "!=" | ">" | "<" | "<=" | ">=" | "&&" | "||" 
<unary-operator>  ::= "-" | "!"
<func-name>       ::= <identifier> | <build-in-defs>
<build-in-defs>   ::= "type" | "parent" | "root" | "name" | "fold" | "random" | "timestamp" | "len"
<function>        ::= "func" <func-name> ":" <function-body>
<function-body>   ::= <params-list> "=>" <value> | <value>
<params-list>     ::= <identifier> | <identifier> "," <params-list>
<value>           ::= "(" <value> ")" | <unary-operator> <value> | <root-value> | 
                      <root-value> <property-path> | <value> <binary-operator> <value> |
                      "if" <value> "then" <value> else <value> |
                      "match" <value> "{" <match-cases> "}"
<match-case>      ::= <literal> ":" <value> | "*" ":" <value>
<match-cases>     ::= <match-case> | <match-case> <match-cases>
<root-value>      ::= <literal> | <identifier> | "_"
<property-path>   ::= "." <property> | "." <property> <property-path>
<property>        ::= <non-def-prop> "[" <value> "]"
<subfield-name>   ::= <identifier> | "*"
<non-def-prop>    ::= <subfield-name> | "$" <func-name> | "$" <func-name> "(" values ")"
<merge-name>      ::= <identifier>
<merge>           ::= "merge" <identifier> ":" <merge-body>
<merge-body>      ::= <merge-case> | <merge-case> "," <merge-case>
<merge-case>      ::= "$." <merge-name> | "$." <subfield> "$" <merge-name> | "$.*.$" <merge-name>
<definition>      ::= <function> | <merge>
<program>         ::= <definition> | <definition> <program>
<comment>         ::= "//" <chars>

```




## Solution diagram
![replication](https://www.plantuml.com/plantuml/svg/ZPJFJy8m5CVl_IjUk0092_7dWQY1G3GS4B9v8nvgzwABfPrTXnV-U1_hm6uMTOScw_VxUUstU-kuiDpOSYF1O2upmMsc5MCsin8XneyI2miBdQ9aB2Td9hASAmkTgLRMQ2dHm7hw-Dm1Ne0tfqrAurcJIQms_1KNND58NB9mN6lUiUTDDgwtfucBLuxpvXnpqMQg_K-4eoO7ofjrt6MJMvKlIVa2wrNYYFhTUQ-QthcogBXiHvlNQ5XsbQuPUpMwpE41BTnB_Sc0ddLqvn_aQA_6lVT-9Ne2sLfLA-l1sTB87TvA0mfRelQEPjpotjOXwvie-gd694EbxtOxXYezH5sLTZX3-KjSSb3S0Mm78FuLce23Vm0cbxVJnrD7u3NCwFY2lL05Ly83Ajphd15C3H8DNAMGqwTEANS5SM6pxLxn_O5oTLi54iIJFWoxyBISBKVmQNWEcG9H5bPlO8D5GNcWEsGe2uI91UJY3HJzfDKYTTdePDwq5rem5FXKouJuFngeTNP9AGG5EKOmzLLpJD7mDplmczuRKpnztZ-RioRZLdEEQQHBy0i0 "replication")