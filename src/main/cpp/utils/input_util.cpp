#include <iostream>
#include <string>
#include <limits>

#include "string_util.h"
#include "input_util.h"

using std::cout;
using std::endl;
using std::cin;

/**
 * Get user input is y ( Y/Yes ) or n ( N/No )
 * If user input an unmatched choice,this function will retry until user entered a legal choice.
 *
 * @since 1.0.0
 */
bool getInputBoolean()
{
    while (true)
    {
        cout << "Enter y/yes or n/no : ";

        std::string input;
        std::getline(std::cin, input);

        input = toLower(input);

        if (input == "y" || input == "yes") return true;
        if (input == "n" || input == "no") return false;

        cout << "Unsupported choice: " << input << ",enter y/yes or n/no" << endl;
    }
}

/**
 * Get user input is y ( Y/Yes ) or n ( N/No )
 * If user input an unmatched choice,this function will return the default boolean value.
 *
 * @since 1.0.0
 */
bool getInputBoolean(const bool def)
{
    cout << "Enter y/yes or n/no ( default " << def << " ) : ";

    std::string input;
    std::getline(std::cin, input);

    input = toLower(input);

    if (input == "y" || input == "yes") return true;
    if (input == "n" || input == "no") return false;

    return def;
}

/**
 * Stuck until input 'enter'
 *
 * @since 1.0.0
 */
void parse()
{
    cin.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    cin.get();
}