# telco-billing

This project is a simple system to automate the billing process
of telecommunication systems.


It is designed to take itemized bills provided by PlusNet or Versatel
and generate full blown billings. The generation uses Latex and
internal templates for itemized bills and an overview.

#### Details

PlusNet provides it's data as dBase files and Versatel as CSV files.
The headers are currently hard-coded and may be replaced by a more flexible
systems if needed. The input files are parsed and the calls are assigned to the customers
connection points. For every customer the fees for the connection point is determined
and the internal templates are filled. The last part is the bill generation
which utilizes latex in a clean temporary enviroment for each bill.


#### Configuration

This project uses clojure maps as schema for the customer database
and the configuration file.

A simple configuration file is as follows:

```clojure
{
  ; point to the latex template and customer database
  :template "resources/example-template"
  :db-file "resources/example.db"
  ; define the billing cost position and the starting billing number
  :cost-position "00",
  :bill-number-prefix "ABC", ; optional prefix for the bill number
  :bill-number "100", ; a countalbe number for the bill
  :bill-date "12-2019" ; the date of the bill
  :basic-fees 20 ; basic fees for a connection point
  :default-name "Example Con" ; default name of the connection point
  :default-fees 1 ; when no other tzone can be applied
  ; define the zoning to distinguish between different duration fees
  ; The zoning is applied to a call iff some of the defined Zone numbers or names
  ; match with the one defined by the call, otherwise the default-fees are applied.
  :tzones [ ; fees in euro per minutes
   ;; example german landline
   {:name "Festnetz (dt.)" ; Name on the bill
    :zone [0 2 8 "City" "Deutschland"] ; Zone definition from provider
    :fees 0.01}] ; fees for the zone
}
```

An example customer db is the following:

```clojure
{
    :customers [
        {
            :name "Customer 1"
            :customer 1001
            :street "Some Street 1"
            :town "0000 Some City"
            :connection-points [131 231]
        }
        {
            :name "Customer 2"
            :customer 1002
            :street "Some Street 2"
            :town "0000 Some City2"
            ;; connection points are provided as list
            ;; but clojure forms are evaluated to make the description
            ;; easier and shorter.
            :connection-points [
               (filter (complement #{416 417 421 422 423 424}) (range 401 443))
            ]
        }
    ]
}

```

## Installation

Either clone this repository or download the precompiled jar file
from `https://github.com/santifa/telco-billing`.

The system assumes that `pdflatex` is available on the path and
that the template always has a `main.tex` file.

## Usage

Build the project with `lein uberjar` or fetch a packaged jar.

    $ java -jar telco-billing-0.1.0-standalone.jar <file1> <file2> ....

    $ java -jar telco-billing-0.1.0-standalone.jar -c conf.cnf -d customers.db \
      versatel-f1.csv plusnet-f1.dbf

## Options

The following options are accepted and the default paths refer to the resource
folder which is not present if you only use the jar file.

```
-h, --help                             - Show help
-o, --output PATH [out]                - Set output directory
-d, --db PATH [resources/customer.db]  - Set the customer database
-c, --config PATH [resources/conf.cnf] - Set the configuration file
```

## Development

Compile the project with:

``` clojure
lein uberjar
```

Or run the project directly with:

``` clojure
lein run <arguments>
```

### Bugs

If you find some bug fill an issue.

## License

Copyright © 2020 Henrik Jürges <juerges.henrik@gmail.com>

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
