# Getting started

Who uses which app, how to sign in, and the path from a customer's request to a test result.
Running the test itself is in [`running-a-test.md`](running-a-test.md); administration in
[`settings-and-admin.md`](settings-and-admin.md).

## Two apps, two places

| App | Where it runs | What it is for |
|---|---|---|
| **cms** | the cloud host | Managing customers, projects, samples, test parameters and results — no hardware. |
| **command-deck** | the PC wired to the test bench | Everything cms has, plus the live force readout and **Execute test**. |

Both open in a browser. Only the command-deck can run a test, because only the bench PC is
connected to the load cell and the drive. A command-deck that shows a red
**SIMULATED HARDWARE** bar under the title is not connected to a bench: its force values are
generated, not measured.

## Signing in

Every page needs a login; an unauthenticated visit lands on the sign-in page. Your name and
**Sign out** sit in the top bar. Accounts are created by an administrator
([`settings-and-admin.md`](settings-and-admin.md#users-and-roles)); there is no self sign-up.

There are two roles. **USER** works with the records described below. **ADMIN** can in addition
manage users, edit the settings, and edit or delete records shared with everyone.

## Records you own and records you share

Customers, projects, samples, test parameters and test results each carry an **Owner**. A
record you own is visible only to you (and to administrators). Leave the owner empty and the
record is **shared**: every user can see and use it, but only an administrator can change or
delete it afterwards. Sharing is therefore a one-way step for a non-admin. A shared project
may only reference a shared customer.

## The menu

| Menu entry | What you find there |
|---|---|
| **Deck** | The live force reading of the load cell, with **Start** / **Stop** buttons that switch the live feed on and off. command-deck only. |
| **Customer** | Customers a project is done for. |
| **Projects** | A customer's request; every project belongs to one customer. The **Export** link in each row downloads the project's results as an Excel workbook (`.xlsx`). |
| **Sample** | The physical parts to test. Each belongs to a project and carries model, manufacturer, year of manufacture, and its materials, gear types and gear standards. |
| **Test** | Pick **Destructive**, **Cyclic** or **Time Cyclic**, then create or edit test parameters of that type. |
| **System** | The lookup lists: gear types, gear standards, materials. |
| **Execute test** | Set up a test run and start it. command-deck only. |

## From request to result

1. Create (or pick) the **customer**, then a **project** for it.
2. Create the **samples** to be tested under that project.
3. Under **Test**, create a **test parameter** of the right type: speed, ramps and the force
   limits the run must respect. Which values are accepted is listed in
   [`running-a-test.md`](running-a-test.md#test-parameters).
4. On the bench PC, open **Execute test**, combine a test parameter with a sample in a new row,
   save it, and press **Run test** ([`running-a-test.md`](running-a-test.md)).
5. Afterwards the same row links to the **Results** (force chart and figures), the **Images**
   (photos you upload), and the project's **Export**.

The vocabulary used here is defined in
[`dev/overview/glossary.md`](../dev/overview/glossary.md).
